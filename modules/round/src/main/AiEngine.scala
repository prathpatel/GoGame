package lila.round

import play.api.Configuration

import goban.{ Bot, Gtp, GtpConnection, GtpEngine }

/** Chooses the computer's moves and estimates dead stones.
  *
  * Uses KataGo (or any GTP engine) when `round.ai.katago.enabled` is set, for levels from
  * `min_level` up; lower levels, and any engine failure or timeout, use the built-in
  * Monte-Carlo [[goban.Bot]].
  */
final class AiEngine(config: AiEngine.Config)(using Executor):

  import AiEngine.*

  private val katago: Option[GtpEngine] = config.katago.map: k =>
    GtpEngine(
      () => GtpConnection.process(k.command, k.workDir),
      // same rules as the server: positional superko, area scoring, no suicide
      init = List("kata-set-rules koPOSITIONALscoreAREAtaxNONEsui0button0"),
      timeout = k.timeout
    )

  /** The engine's own name ("KataGo"), once it answered. */
  @volatile private var engineName: Option[String] = None

  // start the engine now: loading the network takes a few seconds
  katago.foreach:
    _.run(_.send("name")).onComplete:
      case scala.util.Success(name) =>
        engineName = Some(name)
        logger.info(s"AI engine ready: $name")
      case scala.util.Failure(e) =>
        logger.warn(s"AI engine failed to start (${e.getMessage}), the built-in bot will play")

  def usesEngine(level: Int): Boolean = config.katago.exists(level >= _.minLevel)

  /** Which engine plays this level, for display: None for the built-in bot. */
  def engineFor(level: Int): Option[String] = engineName.filter(_ => usesEngine(level))

  def move(game: goban.Game, level: Int): Fu[Choice] =
    config.katago
      .filter(level >= _.minLevel)
      .flatMap(k => katago.map(k -> _))
      .fold(Future(builtIn(game, level))): (k, engine) =>
        val visits = k.visits.lift(level - k.minLevel).orElse(k.visits.lastOption).getOrElse(100)
        engine
          .genmove(game, List(s"kata-set-param maxVisits $visits"))
          .map:
            case Gtp.Answer.Resign if k.resign => Choice.Resign
            case Gtp.Answer.Play(m) if game.position.play(m).isRight => Choice.Play(m)
            case answer =>
              logger.warn(s"AI engine answer refused: $answer, using the built-in bot")
              builtIn(game, level)
          .recover: e =>
            logger.warn(s"AI engine failed (${e.getMessage}), using the built-in bot")
            builtIn(game, level)

  /** Dead stones once both players passed, as a starting point for counting. */
  def deadStones(game: goban.Game): Fu[Set[goban.Point]] =
    katago.fold(fuccess(Bot.estimateDead(game))):
      _.deadStones(game).recover: e =>
        logger.warn(s"AI engine dead stones failed (${e.getMessage}), using playouts")
        Bot.estimateDead(game)

  private def builtIn(game: goban.Game, level: Int): Choice =
    Choice.Play(Bot.move(game, level).move)

  def close(): Unit = katago.foreach(_.close())

object AiEngine:

  enum Choice:
    case Play(move: goban.Move)
    case Resign

  case class KataGo(
      command: List[String],
      workDir: Option[java.io.File],
      minLevel: Int,
      /** maxVisits for levels minLevel, minLevel + 1, ... 8 */
      visits: List[Int],
      timeout: FiniteDuration,
      resign: Boolean
  )

  case class Config(katago: Option[KataGo])

  def config(app: Configuration): Config =
    Config(
      app
        .getOptional[Configuration]("round.ai.katago")
        .filter(_.getOptional[Boolean]("enabled").contains(true))
        .map: c =>
          KataGo(
            command = c.get[String]("command").split(' ').toList.filter(_.nonEmpty),
            workDir = c.getOptional[String]("work_dir").filter(_.nonEmpty).map(java.io.File(_)),
            minLevel = c.getOptional[Int]("min_level").getOrElse(4),
            visits = c.getOptional[Seq[Int]]("visits").fold(List(1, 4, 16, 64, 256))(_.toList),
            timeout = c.getOptional[FiniteDuration]("timeout").getOrElse(30.seconds),
            resign = c.getOptional[Boolean]("resign").getOrElse(true)
          )
    )
