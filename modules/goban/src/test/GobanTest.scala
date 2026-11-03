package goban

import chess.Color

class GobanTest extends munit.FunSuite:

  val nine = BoardSize.Nine

  def pt(s: String, size: Int = 9) = Point.fromHuman(s, size).get
  def play(moves: String*): Either[chess.ErrorStr, Game] =
    Game.replay(nine, Komi(7.5), 0, moves.map(m => Move.parse(m, 9).get))
  def board(rows: String*) = Board.parse(nine, rows.mkString).get

  test("black moves first and colors alternate"):
    val g = Game(nine, Komi(7.5), 0, None)
    assertEquals(g.player, Color.Black)
    assertEquals(g.play(Move.Play(pt("E5"))).map(_.player), Right(Color.White))

  test("coordinates round trip"):
    assertEquals(pt("A9").index, 0)
    assertEquals(pt("J1").index, 80)
    assertEquals(pt("C3", 19).human(19), "C3")
    assertEquals(Point.fromSgf("pd", 19).map(_.human(19)), Some("Q16"))
    assertEquals(Move.parse("pass", 9), Some(Move.Pass))

  test("cannot play on an occupied point"):
    assert(play("E5", "E5").isLeft)

  test("single stone capture"):
    // white E5 surrounded by black
    val g = play("D5", "E5", "F5", "A1", "E4", "A2", "E6").toOption.get
    assert(g.board.isEmpty(pt("E5")))
    assertEquals(g.prisoners(Color.Black), 1)
    assertEquals(g.position.lastCaptures, Set(pt("E5")))

  test("suicide is illegal"):
    val g = play("D5", "A1", "F5", "A2", "E4", "A3", "E6").toOption.get
    assert(g.position.play(Move.Play(pt("E5"))).isLeft)

  test("capturing is not suicide"):
    // white plays into a point with no liberties, but captures black
    val b = board(
      ".bw......",
      "bw.......",
      "w........",
      ".........",
      ".........",
      ".........",
      ".........",
      ".........",
      "........."
    )
    val pos = Position(b, Color.White)
    val next = pos.play(Move.Play(pt("A9")))
    assert(next.isRight)
    assertEquals(next.toOption.get.lastCaptures.size, 2)

  test("ko cannot be retaken immediately"):
    val b = board(
      ".bw......",
      "b.bw.....",
      ".bw......",
      ".........",
      ".........",
      ".........",
      ".........",
      ".........",
      "........."
    )
    // white captures the black stone at C8 by playing B8
    val p1 = Position(b, Color.White).play(Move.Play(pt("B8"))).toOption.get
    assertEquals(p1.lastCaptures, Set(pt("C8")))
    // black retaking at C8 would repeat the previous position
    assert(p1.play(Move.Play(pt("C8"))).isLeft)
    assertEquals(p1.koPoint, Some(pt("C8")))
    // after a ko threat exchange, black may retake
    val p2 = p1.play(Move.Play(pt("H2"))).flatMap(_.play(Move.Play(pt("H3")))).toOption.get
    assert(p2.play(Move.Play(pt("C8"))).isRight)

  test("two passes end play"):
    val g = play("E5", "pass", "pass").toOption.get
    assert(g.bothPassed)
    assert(!g.resume.bothPassed)

  test("handicap places stones and white moves first"):
    val g = Game(BoardSize.Nineteen, Komi(0.5), 4, None)
    assertEquals(g.board.stoneCount(Color.Black), 4)
    assertEquals(g.player, Color.White)
    assertEquals(g.ply.turn, Color.White)

  test("even game ply turn matches black to move"):
    val g = Game(nine, Komi(7.5), 0, None)
    assertEquals(g.ply.turn, Color.Black)
    assertEquals(g.play(Move.Pass).toOption.get.ply.turn, Color.White)

  test("area scoring counts stones and territory"):
    // black owns the left 4 columns, white the right 4, column E is a black wall
    val rows = (1 to 9).map(_ => "....bw...")
    val s = Scoring.area(board(rows*), Set.empty, Komi(7.5))
    assertEquals(s.stones(Color.Black), 9)
    assertEquals(s.stones(Color.White), 9)
    assertEquals(s.territory(Color.Black), 36)
    assertEquals(s.territory(Color.White), 27)
    assertEquals(s.black, 45d)
    assertEquals(s.white, 43.5)
    assertEquals(s.result, "B+1.5")

  test("dead stones are removed and counted for the opponent"):
    val rows = Seq(
      "....bw...",
      "....bw.b.",
      "....bw...",
      "....bw...",
      "....bw...",
      "....bw...",
      "....bw...",
      "....bw...",
      "....bw..."
    )
    val b = board(rows*)
    val dead = Scoring.toggleDead(b, Set.empty, pt("H8"))
    assertEquals(dead, Set(pt("H8")))
    val s = Scoring.area(b, dead, Komi(7.5))
    assertEquals(s.territory(Color.White), 27)
    assertEquals(s.deadCaptured(Color.White), 1)
    assertEquals(Scoring.toggleDead(b, dead, pt("H8")), Set.empty[Point])

  test("sgf round trip"):
    val g = play("E5", "C3", "pass", "G7").toOption.get
    val sgf = Sgf.write(g, Sgf.Tags(black = Some("alice"), white = Some("bob"), result = Some("B+R")))
    assert(sgf.contains("SZ[9]"))
    assert(sgf.contains(";B[ee];W[cg];B[];W[gc]"))
    val parsed = Sgf.read(sgf).toOption.get
    assertEquals(parsed.size, nine)
    assertEquals(parsed.moves, g.moves.toList)
    assertEquals(parsed.tags.black, Some("alice"))

  test("sgf reader follows the main line"):
    val parsed = Sgf.read("(;SZ[9]KM[6.5];B[ee](;W[cc];B[gg])(;W[dd]))").toOption.get
    assertEquals(parsed.moves.map(_.key(9)), List("ee", "cc", "gg"))
    assertEquals(parsed.komi.value, 6.5)

  test("bot plays a legal move and captures when it can"):
    // white stone in atari at E5: black should take it
    val g = play("D5", "E5", "F5", "A1", "E4", "A2").toOption.get
    val r = Bot.move(g, 6, seed = 1L)
    assert(g.position.play(r.move).isRight)
    assertEquals(r.move, Move.Play(pt("E6")))

  test("bot passes back at the end of a won game"):
    val rows = (1 to 9).map(_ => "....b.w..")
    val g0 = Game(nine, Komi(0.5), 0, None).copy(position = Position(board(rows*), Color.Black, consecutivePasses = 1))
    assertEquals(Bot.move(g0, 5, seed = 3L).move, Move.Pass)

  test("dead stone estimation finds a lone invader"):
    val rows = Seq(
      "...bw....",
      "...bw....",
      "...bw....",
      "...bw....",
      "...bw.b..",
      "...bw....",
      "...bw....",
      "...bw....",
      "...bw...."
    )
    val g = Game(nine, Komi(7.5), 0, None).copy(position = Position(board(rows*), Color.Black, consecutivePasses = 2))
    assertEquals(Bot.estimateDead(g), Set(pt("G5")))

  test("bot does not pass early in the game, even at level 1"):
    val g = play("E5", "C3").toOption.get
    (1L to 20L).foreach: seed =>
      assertNotEquals(Bot.move(g, 1, seed).move, Move.Pass, s"seed $seed")
