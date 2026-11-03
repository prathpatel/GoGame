package goban

import java.io.{ BufferedReader, InputStreamReader, OutputStreamWriter, PipedInputStream, PipedOutputStream }
import scala.concurrent.Await
import scala.concurrent.duration.*

class GtpTest extends munit.FunSuite:

  val nine = BoardSize.Nine

  /** A minimal GTP engine running in a thread: answers with a fixed move. */
  class FakeEngine(answer: String, delay: Duration = Duration.Zero):
    val received = scala.collection.mutable.ListBuffer.empty[String]
    def connect(): GtpConnection =
      val toEngine = PipedOutputStream()
      val engineIn = BufferedReader(InputStreamReader(PipedInputStream(toEngine)))
      val fromEngine = PipedOutputStream()
      val clientIn = BufferedReader(InputStreamReader(PipedInputStream(fromEngine)))
      val engineOut = OutputStreamWriter(fromEngine)
      val t = Thread: () =>
        try
          var line = engineIn.readLine()
          while line != null && line != "quit" do
            received.synchronized(received += line)
            val reply =
              if line.startsWith("genmove") then
                if delay > Duration.Zero then Thread.sleep(delay.toMillis)
                s"= $answer"
              else if line.startsWith("final_status_list") then "= C3 D4"
              else if line.startsWith("unknown") then "? unknown command"
              else "="
            engineOut.write(reply + "\n\n")
            engineOut.flush()
            line = engineIn.readLine()
        catch case _: Exception => ()
      t.setDaemon(true)
      t.start()
      // closing behaves like killing a process: both of its streams end
      GtpConnection.streams(clientIn, OutputStreamWriter(toEngine), () => { engineIn.close(); fromEngine.close() })

  // long enough for a real engine to load its networks
  def await[A](f: scala.concurrent.Future[A]) = Await.result(f, 2.minutes)

  test("vertices use GTP coordinates"):
    assertEquals(Gtp.vertex(Point.fromSgf("pd", 19).get, 19), "Q16")
    assertEquals(Gtp.parseVertex("Q16", 19), Some(Move.Play(Point.fromSgf("pd", 19).get)))
    assertEquals(Gtp.parseVertex("PASS", 9), Some(Move.Pass))
    assertEquals(Gtp.parseVertex("Z99", 9), None)

  test("setup commands replay the game, including handicap and passes"):
    val g = Game.replay(BoardSize.Nineteen, Komi(0.5), 2, List(Move.parse("dd", 19).get, Move.Pass)).toOption.get
    assertEquals(
      Gtp.setupCommands(g),
      List("boardsize 19", "clear_board", "komi 0.5", "set_free_handicap Q16 D4", "play W D16", "play B pass")
    )

  test("even games start with black"):
    val g = Game(nine, Komi(7.5), 0, None).play(Move.parse("ee", 9).get).toOption.get
    assertEquals(Gtp.setupCommands(g).last, "play B E5")

  test("genmove asks for the right color and parses the answer"):
    val fake = FakeEngine("c3")
    val engine = GtpEngine(() => fake.connect(), init = List("unknown-init-command"))
    val g = Game(nine, Komi(7.5), 0, None).play(Move.parse("ee", 9).get).toOption.get
    assertEquals(
      await(engine.genmove(g, List("kata-set-param maxVisits 10"))),
      Gtp.Answer.Play(Move.Play(Point.fromHuman("C3", 9).get))
    )
    assert(fake.received.contains("genmove W"))
    assert(fake.received.contains("kata-set-param maxVisits 10"))
    engine.close()

  test("resign and dead stones"):
    val engine = GtpEngine(() => FakeEngine("resign").connect())
    val g = Game(nine, Komi(7.5), 0, None)
    assertEquals(await(engine.genmove(g)), Gtp.Answer.Resign)
    assertEquals(await(engine.deadStones(g)), Set("C3", "D4").flatMap(Point.fromHuman(_, 9)))
    engine.close()

  test("a slow engine times out, and the next request gets a fresh engine"):
    var connections = 0
    val engine = GtpEngine(
      () =>
        connections += 1
        (if connections == 1 then FakeEngine("e5", delay = 5.seconds) else FakeEngine("e5")).connect()
      ,
      timeout = 300.millis
    )
    val g = Game(nine, Komi(7.5), 0, None)
    intercept[java.util.concurrent.TimeoutException](await(engine.genmove(g)))
    assertEquals(await(engine.genmove(g)), Gtp.Answer.Play(Move.parse("ee", 9).get))
    assertEquals(connections, 2)
    engine.close()

  // Runs against a real engine when KATAGO_COMMAND is set, e.g.
  // KATAGO_COMMAND="$PWD/katago/katago gtp -model $PWD/katago/model.bin.gz \
  //   -human-model $PWD/katago/human.bin.gz -config $PWD/conf/katago.cfg" (see README)
  test("real engine plays a legal move and lists dead stones"):
    assume(sys.env.contains("KATAGO_COMMAND"), "KATAGO_COMMAND is not set")
    val command = sys.env("KATAGO_COMMAND").split(' ').toList
    val engine = GtpEngine(() => GtpConnection.process(command), timeout = 120.seconds)
    val g = Game.replay(nine, Komi(7.5), 0, Game.parseMoves("ee cc", nine)).toOption.get
    await(engine.genmove(g, List("kata-set-param maxVisits 8"))) match
      case Gtp.Answer.Play(m) => assert(g.position.play(m).isRight, s"illegal move $m")
      case Gtp.Answer.Resign => fail("resigned in the opening")
    // a lone white stone inside black's area is dead
    val rows = Seq("....bw...", "....bw...", ".w..bw...", "....bw...", "....bw...", "....bw...", "....bw...",
      "....bw...", "....bw...")
    val board = Board.parse(nine, rows.mkString).get
    val ended = Game(nine, Komi(7.5), 0, None).copy(position = Position(board, chess.Color.Black))
    // GTP can only set up positions through moves, so feed the stones as moves with passes in between
    val setup = board.allPoints.toList.filterNot(board.isEmpty).sortBy(p => if board(p) == Stone.black then 0 else 1)
    val (blacks, whites) = setup.partition(board(_) == Stone.black)
    val moves = blacks.map(Some(_)).zipAll(whites.map(Some(_)), None, None).flatMap: (b, w) =>
      List(b.fold(Move.Pass)(Move.Play(_)), w.fold(Move.Pass)(Move.Play(_)))
    val game = Game.replay(nine, Komi(7.5), 0, moves).toOption.get
    assertEquals(game.board, ended.board)
    assert(await(engine.deadStones(game)).contains(Point.fromHuman("B7", 9).get))
    engine.close()
