package lila.setup

import chess.Clock
import play.api.data.*
import play.api.data.Forms.*

import lila.common.Form.{ *, given }

object SetupForm:

  import Mappings.*

  val filter = Form(single("local" -> text))

  def aiFilled: Form[AiConfig] = ai.fill(AiConfig.default)

  lazy val ai = Form:
    mapping(
      "size" -> size,
      "timeMode" -> timeMode,
      "time" -> time,
      "increment" -> increment,
      "days" -> days,
      "level" -> level,
      "color" -> color,
      "handicap" -> handicap
    )(AiConfig.from)(_.>>)
      .verifying("Invalid handicap", _.validHandicap)

  def friendFilled(using Option[Me]): Form[FriendConfig] = friend.fill(FriendConfig.default)

  def friend(using me: Option[Me]) = Form:
    mapping(
      "size" -> size,
      "timeMode" -> timeMode,
      "time" -> time,
      "increment" -> increment,
      "days" -> days,
      "mode" -> mode(withRated = me.isDefined),
      "color" -> color,
      "handicap" -> handicap
    )(FriendConfig.from)(_.>>)
      .verifying("Invalid clock", _.validClock)
      .verifying("Invalid speed", _.validSpeed(me.exists(_.isBot)))
      .verifying("Can't create rated unlimited game", !_.isRatedUnlimited)
      .verifying("Invalid handicap", _.validHandicap)
      .verifying("Handicap games can't be rated", c => c.handicap == 0 || c.rated.no)

  def hookFilled(timeModeString: Option[String])(using me: Option[Me]): Form[HookConfig] =
    hook.fill(HookConfig.default(me.isDefined).withTimeModeString(timeModeString))

  def hook(using me: Option[Me]) = Form:
    mapping(
      "size" -> size,
      "timeMode" -> timeMode,
      "time" -> time,
      "increment" -> increment,
      "days" -> days,
      "mode" -> mode(me.isDefined),
      "ratingRange" -> optional(ratingRange),
      "color" -> lila.common.Form.empty
    )(HookConfig.from)(_.>>)
      .verifying("Invalid clock", _.validClock)
      .verifying("Can't create rated unlimited game", !_.isRatedUnlimited)

  def toFriend = Form(single("username" -> lila.common.Form.username.historicalField))

  object api extends lila.core.setup.SetupForm:

    lazy val clockMapping =
      mapping(
        "limit" -> number.into[Clock.LimitSeconds].verifying(l => l >= 0 && l <= 10800),
        "increment" -> increment
      )(Clock.Config.apply)(unapply)
        .verifying("Invalid clock", _.estimateTotalTime.nonZero)

    lazy val clock = "clock" -> optional(clockMapping)

    lazy val optionalDays = "days" -> optional(days)

    lazy val boardSize = "size" -> Mappings.size

    lazy val message = "message" -> optional(
      nonEmptyText(maxLength = 8_000).verifying(
        "The message must contain {game}, which will be replaced with the game URL.",
        msg => msg.contains("{game}") || msg.toLowerCase.contains("{nomessage}")
      )
    )

    val rules = "rules" -> optional:
      import lila.core.game.GameRule
      lila.common.Form.strings
        .separator(",")
        .verifying(_.forall(GameRule.byKey.contains))
        .transform[Set[GameRule]](rs => rs.flatMap(GameRule.byKey.get).toSet, _.map(_.toString).toList)
