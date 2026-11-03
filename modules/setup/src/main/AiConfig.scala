package lila.setup

import chess.{ ByColor, Clock }
import scalalib.model.Days

import lila.core.game.{ IdGenerator, NewPlayer, Source }
import lila.core.user.GameUser
import lila.lobby.TriColor

case class AiConfig(
    size: goban.BoardSize,
    timeMode: TimeMode,
    time: Double,
    increment: Clock.IncrementSeconds,
    days: Days,
    level: Int,
    color: TriColor,
    handicap: Int = 0
) extends Config
    with WithColor:

  def >> = (size.size, timeMode.id, time, increment, days, level, color.name, handicap).some

  private def game(user: GameUser)(using idGenerator: IdGenerator, newPlayer: NewPlayer): Fu[Game] =
    idGenerator
      .withUniqueId:
        lila.core.game
          .newGame(
            go = makeGame,
            players = ByColor: c =>
              if creatorColor == c
              then newPlayer(c, user)
              else newPlayer.anon(c, level.some),
            rated = chess.Rated.No,
            source = Source.Ai,
            daysPerTurn = makeDaysPerTurn,
            pgnImport = None
          )
    .dmap(_.start)

  def pov(user: GameUser)(using IdGenerator, NewPlayer) = game(user).dmap(Pov(_, creatorColor))


object AiConfig extends BaseConfig:

  def from(
      s: Int,
      tm: Int,
      t: Double,
      i: Clock.IncrementSeconds,
      d: Days,
      level: Int,
      c: String,
      h: Int
  ) =
    new AiConfig(
      size = goban.BoardSize.orDefault(s),
      timeMode = TimeMode(tm).err(s"Invalid time mode $tm"),
      time = t,
      increment = i,
      days = d,
      level = level,
      color = TriColor(c).err("Invalid color " + c),
      handicap = h
    )

  val default = AiConfig(
    size = goban.BoardSize.Nine,
    timeMode = TimeMode.Unlimited,
    time = 5d,
    increment = Clock.IncrementSeconds(8),
    days = Days(2),
    level = 1,
    color = TriColor.default
  )

  val levels = (1 to 8).toList

  val levelChoices = levels.map { l =>
    (l.toString, l.toString, none)
  }

  import lila.db.BSON
  import lila.db.dsl.{ *, given }

  private[setup] given BSON[AiConfig] with

    def reads(r: BSON.Reader): AiConfig =
      AiConfig(
        size = goban.BoardSize.orDefault(r.intD("s")),
        timeMode = TimeMode.orDefault(r.int("tm")),
        time = r.double("t"),
        increment = r.get("i"),
        days = r.get("d"),
        level = r.int("l"),
        color = TriColor.Black,
        handicap = r.intD("h")
      )

    def writes(w: BSON.Writer, o: AiConfig) =
      bdoc(
        "s" -> o.size.size,
        "tm" -> o.timeMode.id,
        "t" -> o.time,
        "i" -> o.increment,
        "d" -> o.days,
        "l" -> o.level,
        "h" -> o.handicap
      )
