package lila.challenge

import chess.{ Clock, Color, Ply }

final class JoinerTest extends munit.FunSuite:

  val timeControl =
    Challenge.TimeControl.Clock(Clock.Config(Clock.LimitSeconds(300), Clock.IncrementSeconds(0)))

  def make(size: goban.BoardSize, handicap: Int) = Challenge.make(
    size = size,
    handicap = handicap,
    timeControl = timeControl,
    rated = chess.Rated.No,
    color = "white",
    challenger = Challenge.Challenger.Anonymous("secret"),
    destUser = None,
    rematchOf = None
  )

  test("even game: black moves first"):
    val game = ChallengeJoiner.createGame(make(goban.BoardSize.Nineteen, 0), None, None)
    assertEquals(game.go.startedAtPly, Ply.firstMove)
    assertEquals(game.turnColor, Color.Black)
    assertEquals(game.boardSize, goban.BoardSize.Nineteen)
    assertEquals(game.komi.value, 7.5)

  test("handicap game: white moves first, with half a point komi"):
    val game = ChallengeJoiner.createGame(make(goban.BoardSize.Thirteen, 3), None, None)
    assertEquals(game.go.startedAtPly, Ply.initial)
    assertEquals(game.turnColor, Color.White)
    assertEquals(game.handicap, 3)
    assertEquals(game.board.stoneCount(Color.Black), 3)
    assertEquals(game.komi.value, 0.5)

  test("handicap challenges are never rated"):
    val c = Challenge.make(
      size = goban.BoardSize.Nine,
      handicap = 2,
      timeControl = timeControl,
      rated = chess.Rated.Yes,
      color = "random",
      challenger = Challenge.Challenger.Anonymous("secret"),
      destUser = None,
      rematchOf = None
    )
    assert(c.rated.no)
