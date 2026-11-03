package lila.setup

import chess.{ Clock, Rated }
import scalalib.model.Days

import lila.lobby.TriColor

case class FriendConfig(
    size: goban.BoardSize,
    timeMode: TimeMode,
    time: Double,
    increment: Clock.IncrementSeconds,
    days: Days,
    rated: Rated,
    color: TriColor,
    handicap: Int = 0
) extends HumanConfig
    with WithColor:

  def >> = (size.size, timeMode.id, time, increment, days, rated.id.some, color.name, handicap).some

  def isPersistent = timeMode == TimeMode.Unlimited || timeMode == TimeMode.Correspondence

object FriendConfig extends BaseConfig:

  def from(
      s: Int,
      tm: Int,
      t: Double,
      i: Clock.IncrementSeconds,
      d: Days,
      m: Option[Int],
      c: String,
      h: Int
  ) =
    new FriendConfig(
      size = goban.BoardSize.orDefault(s),
      timeMode = TimeMode(tm).err(s"Invalid time mode $tm"),
      time = t,
      increment = i,
      days = d,
      rated = m.fold(Rated.default)(Rated.orDefault),
      color = TriColor(c).err("Invalid color " + c),
      handicap = h
    )

  val default = FriendConfig(
    size = sizeDefault,
    timeMode = TimeMode.Unlimited,
    time = 5d,
    increment = Clock.IncrementSeconds(8),
    days = Days(2),
    rated = Rated.default,
    color = TriColor.default
  )

  import lila.db.BSON
  import lila.db.dsl.{ *, given }

  private[setup] given BSON[FriendConfig] with

    def reads(r: BSON.Reader): FriendConfig =
      FriendConfig(
        size = goban.BoardSize.orDefault(r.intD("s")),
        timeMode = TimeMode.orDefault(r.int("tm")),
        time = r.double("t"),
        increment = r.get("i"),
        days = r.get("d"),
        rated = Rated.orDefault(r.int("m")),
        color = TriColor.Random,
        handicap = r.intD("h")
      )

    def writes(w: BSON.Writer, o: FriendConfig) =
      bdoc(
        "s" -> o.size.size,
        "tm" -> o.timeMode.id,
        "t" -> o.time,
        "i" -> o.increment,
        "d" -> o.days,
        "m" -> o.rated.id,
        "h" -> o.handicap
      )
