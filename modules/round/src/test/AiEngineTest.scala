package lila.round

import scala.concurrent.Await

import play.api.Configuration

class AiEngineTest extends munit.FunSuite:

  given scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val game = goban.Game(goban.BoardSize.Nine, goban.Komi(7.5), 0, None)

  import AiEngine.{ KataGo, Level }

  val humanLevels = List(Level(Some("rank_20k"), 1), Level(Some("rank_5k"), 4), Level(None, 64))

  def engine(command: String, minLevel: Int = 4) = AiEngine(
    AiEngine.Config(
      Some(KataGo(command.split(' ').toList, None, minLevel, List(Level(None, 1)), 5.seconds, resign = true))
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

  test("human ranks set the human-style network, full strength turns it off"):
    val k = KataGo(Nil, None, 1, humanLevels, 5.seconds, resign = true)
    assertEquals(
      k.params(1),
      List(
        "kata-set-param humanSLProfile rank_20k",
        "kata-set-param humanSLChosenMoveProp 1.0",
        "kata-set-param maxVisits 1"
      )
    )
    assertEquals(
      k.params(3),
      List("kata-set-param humanSLChosenMoveProp 0.0", "kata-set-param maxVisits 64")
    )
    // levels past the list use the last entry
    assertEquals(k.params(8), k.params(3))
    assertEquals(k.levelOf(2).flatMap(_.rank), Some("5k"))

  test("without any rank, only the search size is set"):
    val k = KataGo(Nil, None, 1, List(Level(None, 4), Level(None, 32)), 5.seconds, resign = true)
    assertEquals(k.params(2), List("kata-set-param maxVisits 32"))

  test("the default config: KataGo plays every level, from 20 kyu to full strength"):
    val conf = Configuration(com.typesafe.config.ConfigFactory.parseFile(java.io.File("conf/base.conf")))
    val k = AiEngine.config(conf).katago.get
    assertEquals(k.minLevel, 1)
    assertEquals(k.levels.size, 8)
    assertEquals(k.levelOf(1).flatMap(_.rank), Some("20k"))
    assertEquals(k.levelOf(8).flatMap(_.profile), None)
    // existing files in the command are made absolute, the rest is left alone
    assert(k.command.contains("gtp"))
    assert(k.command.exists(a => a.startsWith("/") && a.endsWith("conf/katago.cfg")))
    assert(k.workDir.exists(_.isAbsolute))

  test("older configs with a list of visits still work"):
    val conf = Configuration(
      "round.ai.katago.enabled" -> true,
      "round.ai.katago.command" -> "katago gtp",
      "round.ai.katago.min_level" -> 4,
      "round.ai.katago.visits" -> List(1, 4, 16)
    )
    val k = AiEngine.config(conf).katago.get
    assertEquals(k.levels, List(Level(None, 1), Level(None, 4), Level(None, 16)))
    assertEquals(k.params(8), List("kata-set-param maxVisits 16"))
