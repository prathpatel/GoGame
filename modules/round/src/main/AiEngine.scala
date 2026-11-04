package lila.round

import play.api.Configuration

import goban.{ Bot, Gtp, GtpConnection, GtpEngine }

/** Chooses the computer's moves and estimates dead stones.
  *
  * KataGo plays every level from `min_level` (1 by default) up: levels 1-7 imitate human
  * players from 20 kyu to 9 dan with KataGo's human-style network, level 8 plays at full
  * strength. The built-in Monte-Carlo [[goban.Bot]] is only a fallback: it plays when KataGo
  * is disabled or not installed (see bin/setup-katago), fails, times out or answers an illegal
  * move.
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
        logger.warn(
          s"AI engine failed to start (${e.getMessage}). Run bin/setup-katago to install KataGo; " +
            "until then the built-in bot plays."
        )

  /** How long to wait for an AI move before asking again: longer than the engine timeout. */
  def watchdogDelay: FiniteDuration = config.katago.fold(20.seconds)(_.timeout + 15.seconds)

  def usesEngine(level: Int): Boolean = config.katago.exists(level >= _.minLevel)

  /** Which engine plays this level, for display ("KataGo 10k"): None for the built-in bot. */
  def engineFor(level: Int): Option[String] =
    for
      name <- engineName
      k <- config.katago
      if usesEngine(level)
    yield k.levelOf(level).flatMap(_.rank).fold(name)(r => s"$name $r")

  def move(game: goban.Game, level: Int): Fu[Choice] =
    config.katago
      .filter(level >= _.minLevel)
      .flatMap(k => katago.map(k -> _))
      .fold(Future(builtIn(game, level))): (k, engine) =>
        engine
          .genmove(game, k.params(level))
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

  /** One computer level: a human rank for KataGo's human-style network to imitate
    * ("rank_20k" ... "rank_9d"), or none for full strength, and the search size.
    */
  case class Level(profile: Option[String], visits: Int):
    /** "rank_10k" => "10k" */
    def rank: Option[String] = profile.map(_.split('_').last)

  case class KataGo(
      /** the engine command; arguments that name existing files are made absolute */
      command: List[String],
      /** where the engine runs and writes its logs */
      workDir: Option[java.io.File],
      minLevel: Int,
      /** levels minLevel, minLevel + 1, ... 8 */
      levels: List[Level],
      timeout: FiniteDuration,
      resign: Boolean
  ):
    def levelOf(level: Int): Option[Level] = levels.lift(level - minLevel).orElse(levels.lastOption)

    /** with no rank anywhere, the engine may run without the human-style network */
    private val human = levels.exists(_.profile.isDefined)

    /** GTP commands that set up this level before a move */
    def params(level: Int): List[String] =
      val l = levelOf(level).getOrElse(Level(None, 100))
      val humanParams =
        if !human then Nil
        else
          l.profile.fold(List("kata-set-param humanSLChosenMoveProp 0.0")): p =>
            List(s"kata-set-param humanSLProfile $p", "kata-set-param humanSLChosenMoveProp 1.0")
      humanParams :+ s"kata-set-param maxVisits ${l.visits}"

  case class Config(katago: Option[KataGo])

  /** Relative paths in the config are relative to the directory lila runs in. */
  private def absolute(path: String) = java.io.File(path).getAbsoluteFile

  def config(app: Configuration): Config =
    Config(
      app
        .getOptional[Configuration]("round.ai.katago")
        .filter(_.getOptional[Boolean]("enabled").contains(true))
        .map: c =>
          val minLevel = c.getOptional[Int]("min_level").getOrElse(1)
          KataGo(
            command = c
              .get[String]("command")
              .split(' ')
              .toList
              .filter(_.nonEmpty)
              .map: arg =>
                if !arg.startsWith("-") && java.io.File(arg).exists then absolute(arg).getPath else arg,
            workDir = c.getOptional[String]("work_dir").filter(_.nonEmpty).map(absolute),
            minLevel = minLevel,
            levels = c
              .getOptional[Seq[Configuration]]("levels")
              .map(_.toList.map: l =>
                Level(l.getOptional[String]("profile").filter(_.nonEmpty), l.getOptional[Int]("visits") | 100))
              .orElse:
                // older configs: a list of maxVisits, no ranks
                c.getOptional[Seq[Int]]("visits").map(_.toList.map(Level(None, _)))
              .filter(_.nonEmpty)
              .getOrElse(List.fill(9 - minLevel)(Level(None, 64))),
            timeout = c.getOptional[FiniteDuration]("timeout").getOrElse(30.seconds),
            resign = c.getOptional[Boolean]("resign").getOrElse(true)
          )
    )
