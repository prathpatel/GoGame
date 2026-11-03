package lila.round

import scala.concurrent.Await


class AiEngineTest extends munit.FunSuite:

  given scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val game = goban.Game(goban.BoardSize.Nine, goban.Komi(7.5), 0, None)

  def engine(command: String, minLevel: Int = 4) = AiEngine(
    AiEngine.Config(
      Some(AiEngine.KataGo(command.split(' ').toList, None, minLevel, List(1), 5.seconds, resign = true))
    )
  )

  def legal(choice: AiEngine.Choice) = choice match
    case AiEngine.Choice.Play(m) => game.position.play(m).isRight
    case AiEngine.Choice.Resign => false

  test("without an engine, the built-in bot plays"):
    val ai = AiEngine(AiEngine.Config(None))
    assert(legal(Await.result(ai.move(game, 5), 10.seconds)))

  test("a missing engine binary falls back to the built-in bot"):
    val ai = engine("/nonexistent/katago gtp")
    assert(legal(Await.result(ai.move(game, 5), 10.seconds)))
    assert(Await.result(ai.deadStones(game), 10.seconds).isEmpty)
    ai.close()

  test("an engine that answers garbage falls back to the built-in bot"):
    // `cat` echoes the commands back: not valid GTP
    val ai = engine("cat")
    assert(legal(Await.result(ai.move(game, 5), 10.seconds)))
    ai.close()

  test("levels below min_level never use the engine"):
    val ai = engine("/nonexistent/katago", minLevel = 7)
    assert(!ai.usesEngine(3))
    assert(ai.usesEngine(7))
    ai.close()
