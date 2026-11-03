package goban

import java.io.{ BufferedReader, InputStreamReader, OutputStreamWriter, Writer }
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ Executors, TimeUnit, TimeoutException }
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

/** Go Text Protocol: how Go engines like KataGo, Leela Zero, GNU Go or Pachi are driven.
  * https://www.lysator.liu.se/~gunnar/gtp/
  */
object Gtp:

  case class Error(message: String) extends Exception(message)

  /** What the engine wants to do. */
  enum Answer:
    case Play(move: Move)
    case Resign

  /** GTP vertex: column letter (A-T, no I) and row counted from the bottom, e.g. "Q16". */
  def vertex(p: Point, size: Int): String = p.human(size)

  def parseVertex(str: String, size: Int): Option[Move] =
    str.trim.toLowerCase match
      case "pass" => Some(Move.Pass)
      case v => Point.fromHuman(v, size).map(Move.Play(_))

  def colorName(c: chess.Color) = if c.black then "B" else "W"

  /** The commands that set up `game`'s current position from scratch. */
  def setupCommands(game: Game): List[String] =
    val n = game.board.width
    val firstColor = Game.colorToMoveAt(game.startedAtPly)
    val handicapStones = Handicap.points(game.size, game.handicap)
    List(s"boardsize $n", "clear_board", s"komi ${Score.fmt(game.komi.value)}") :::
      (if handicapStones.isEmpty then Nil
       else List(s"set_free_handicap ${handicapStones.map(vertex(_, n)).mkString(" ")}")) :::
      game.moves.toList.zipWithIndex.map: (m, i) =>
        val color = if i % 2 == 0 then firstColor else !firstColor
        val v = m match
          case Move.Pass => "pass"
          case Move.Play(p) => vertex(p, n)
        s"play ${colorName(color)} $v"

/** A GTP connection to one engine process. Not thread safe: use [[GtpEngine]]. */
trait GtpConnection:
  def send(command: String): String
  def close(): Unit

object GtpConnection:

  /** Launch an engine process and talk to it on stdin/stdout. Its stderr is discarded. */
  def process(command: List[String], workDir: Option[java.io.File] = None): GtpConnection =
    val builder = ProcessBuilder(command*)
    workDir.foreach(builder.directory)
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    val proc = builder.start()
    streams(
      BufferedReader(InputStreamReader(proc.getInputStream, UTF_8)),
      OutputStreamWriter(proc.getOutputStream, UTF_8),
      () => proc.destroy()
    )

  /** GTP framing over a pair of streams: send a line, read until an empty line. */
  def streams(in: BufferedReader, out: Writer, onClose: () => Unit = () => ()): GtpConnection = new:
    def send(command: String): String =
      out.write(command)
      out.write("\n")
      out.flush()
      // skip blank lines left over from a previous response, then read until the terminating empty line
      var line = in.readLine()
      while line != null && line.trim.isEmpty do line = in.readLine()
      if line == null then throw Gtp.Error("engine closed the connection")
      val lines = scala.collection.mutable.ListBuffer(line)
      line = in.readLine()
      while line != null && line.trim.nonEmpty do
        lines += line
        line = in.readLine()
      val text = lines.mkString("\n")
      if text.startsWith("=") then text.drop(1).trim
      else if text.startsWith("?") then throw Gtp.Error(s"$command: ${text.drop(1).trim}")
      else throw Gtp.Error(s"$command: unexpected response '$text'")
    def close(): Unit =
      Try(out.write("quit\n"))
      Try(out.flush())
      onClose()

/** A long-lived engine shared by all games. Requests are serialized on one thread,
  * and the engine process is restarted after an error or a timeout.
  */
final class GtpEngine(
    connect: () => GtpConnection,
    /** extra commands sent after every (re)connection, e.g. to set the rules */
    init: List[String] = Nil,
    timeout: FiniteDuration = 30.seconds
):
  private val thread = Executors.newSingleThreadExecutor: r =>
    val t = Thread(r, "gtp-engine")
    t.setDaemon(true)
    t
  private given ExecutionContext = ExecutionContext.fromExecutor(thread)
  @volatile private var conn: Option[GtpConnection] = None

  private def connection(): GtpConnection = conn.getOrElse:
    val c = connect()
    conn = Some(c)
    // optional engine-specific commands: ignore the ones this engine doesn't know
    init.foreach(cmd => Try(c.send(cmd)))
    c

  private def reset(): Unit =
    conn.foreach(c => Try(c.close()))
    conn = None

  /** Run `f` on the engine thread, with a timeout. On failure or timeout the engine is restarted. */
  def run[A](f: GtpConnection => A): Future[A] =
    val promise = scala.concurrent.Promise[A]()
    val onTimeout: Runnable = () =>
      if promise.tryFailure(TimeoutException(s"engine did not answer within $timeout")) then
        // unblock the engine thread by killing the process
        conn.foreach(c => Try(c.close()))
    GtpEngine.timer.schedule(onTimeout, timeout.toMillis, TimeUnit.MILLISECONDS)
    Future:
      if !promise.isCompleted then
        try
          val result = f(connection())
          // if we answered too late, the engine was killed: start a fresh one next time
          if !promise.trySuccess(result) then reset()
        catch
          case e: Throwable =>
            reset()
            promise.tryFailure(e)
    promise.future

  /** Ask for a move in `game`'s current position. `params` are sent before `genmove`. */
  def genmove(game: Game, params: List[String] = Nil): Future[Gtp.Answer] = run: c =>
    Gtp.setupCommands(game).foreach(c.send)
    params.foreach(p => Try(c.send(p)))
    val answer = c.send(s"genmove ${Gtp.colorName(game.player)}")
    if answer.equalsIgnoreCase("resign") then Gtp.Answer.Resign
    else
      Gtp
        .parseVertex(answer, game.board.width)
        .map(Gtp.Answer.Play(_))
        .getOrElse(throw Gtp.Error(s"bad move from engine: '$answer'"))

  /** The stones the engine considers dead in `game`'s current position. */
  def deadStones(game: Game): Future[Set[Point]] = run: c =>
    Gtp.setupCommands(game).foreach(c.send)
    c.send("final_status_list dead")
      .split("\\s+")
      .toList
      .filter(_.nonEmpty)
      .flatMap(Point.fromHuman(_, game.board.width))
      .toSet

  def close(): Unit =
    reset()
    thread.shutdownNow()

object GtpEngine:
  private val timer = Executors.newSingleThreadScheduledExecutor: r =>
    val t = Thread(r, "gtp-timeout")
    t.setDaemon(true)
    t
