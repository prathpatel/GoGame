package lila.round

import chess.{ ByColor, Clock, Color, Speed }
import play.api.libs.json.*
import scalalib.data.Preload

import scala.math

import lila.common.Json.given
import lila.core.game.Player as GamePlayer
import lila.core.net.ApiVersion
import lila.core.perf.KeyedPerf
import lila.core.user.{ GameUser, GameUsers, WithPerf }
import lila.game.GameExt.{ expirable, moveTimes, timeForFirstMove }
import lila.game.JsonView.given
import lila.pref.Pref
import lila.round.RoundGame.*

final class JsonView(
    userJsonView: lila.user.JsonView,
    gameJsonView: lila.game.JsonView,
    getSocketStatus: Game => Fu[SocketStatus],
    takebacker: Takebacker,
    moretimer: Moretimer,
    aiEngine: AiEngine,
    isOfferingRematch: lila.core.round.IsOfferingRematch
)(using Executor):


  private def commonPlayerJson(
      g: Game,
      p: GamePlayer,
      user: GameUser,
      withFlags: ExportOptions
  ): JsObject =
    import withFlags.me
    Json
      .obj("color" -> p.color.name)
      .add("user" -> user.match
        case Some(WithPerf(user, perf)) =>
          val p = withFlags.rating.option(KeyedPerf(g.perfKey, perf))
          userJsonView.roundPlayer(user, p).some
        case _ if p.hasUser => userJsonView.ghost.some
        case _ => none)
      .add("rating" -> p.rating.ifTrue(withFlags.rating))
      .add("ratingDiff" -> p.ratingDiff.ifTrue(withFlags.rating))
      .add("provisional" -> (p.provisional.yes && withFlags.rating))
      .add("offeringRematch" -> isOfferingRematch.exec(Pov(g, p).ref))
      .add("offeringDraw" -> p.isOfferingDraw)
      .add("proposingTakeback" -> p.isProposingTakeback)
      .add("prisoners" -> g.go.prisoners(p.color).some)
      .add("berserk" -> p.berserk)
      .add("blindfold" -> p.blindfold)
      .add("blurs" -> (withFlags.blurs.so(blurs(g, p))))

  def playerJson(
      pov: Pov,
      prefs: ByColor[Pref],
      users: GameUsers,
      flags: ExportOptions
  ): Fu[JsObject] = for
    takebackable <- takebacker.isAllowedIn(pov.game, Preload(prefs))
    moretimeable <- moretimer.isAllowedIn(pov.game, Preload(prefs), force = false)
    socket <- getSocketStatus(pov.game)
    pref = prefs(pov.color)
  yield
    import pov.*
    Json
      .obj(
        "game" -> gameJsonView.baseWithDenorm(game),
        "player" -> {
          commonPlayerJson(game, player, users(pov.color), flags) ++ Json
            .obj(
              "id" -> playerId,
              "version" -> socket.version
            )
            .add("onGame" -> (player.isAi || socket.onGame(player.color)))
        },
        "opponent" -> {
          commonPlayerJson(game, opponent, users(pov.opponent.color), flags) ++ Json
            .obj("color" -> opponent.color.name)
            .add("ai" -> opponent.aiLevel)
            .add("aiEngine" -> opponent.aiLevel.flatMap(aiEngine.engineFor))
            .add("isGone" -> (pov.game.forceResignable && socket.isGone(opponent.color)))
            .add("onGame" -> (opponent.isAi || socket.onGame(opponent.color)))
        },
        "pref" ->
          Json
            .obj(
              "animationDuration" -> animationMillis(pov, pref),
              "coords" -> pref.coords,
              "resizeHandle" -> pref.resizeHandle,
              "replay" -> pref.replay,
              "clockTenths" -> pref.clockTenths,
              "moveEvent" -> pref.moveEvent
            )
            .add("is3d" -> pref.is3d)
            .add("clockBar" -> pref.clockBar)
            .add("clockSound" -> pref.clockSound)
            .add("confirmResign" -> (!flags.nvui && pref.confirmResign == Pref.ConfirmResign.YES))
            .add("highlight" -> pref.highlight)
            .add("submitMove" -> submitMovePref(pref, game, flags.nvui))
      )
      .add("clock" -> game.clock.map(clockJson))
      .add("correspondence" -> game.correspondenceClock)
      .add("takebackable" -> takebackable)
      .add("moretimeable" -> moretimeable)
      .add("expiration" -> game.expirable.option:
        Json.obj(
          "idleMillis" -> (nowMillis - game.movedAt.toMillis),
          "millisToMove" -> game.timeForFirstMove.millis
        ))

  private def commonWatcherJson(
      g: Game,
      p: GamePlayer,
      user: GameUser,
      withFlags: ExportOptions
  ): JsObject =
    import withFlags.me
    Json
      .obj(
        "color" -> p.color.name,
        "name" -> p.name
      )
      .add("user" -> user.match
        case Some(WithPerf(user, perf)) =>
          userJsonView.roundPlayer(user, withFlags.rating.option(KeyedPerf(g.perfKey, perf))).some
        case _ if p.hasUser => userJsonView.ghost.some
        case _ => none)
      .add("ai" -> p.aiLevel)
      .add("aiEngine" -> p.aiLevel.flatMap(aiEngine.engineFor))
      .add("rating" -> p.rating.ifTrue(withFlags.rating))
      .add("ratingDiff" -> p.ratingDiff.ifTrue(withFlags.rating))
      .add("provisional" -> (p.provisional.yes && withFlags.rating))
      .add("prisoners" -> g.go.prisoners(p.color).some)
      .add("berserk" -> p.berserk)
      .add("blurs" -> (withFlags.blurs.so(blurs(g, p))))

  def watcherJson(
      pov: Pov,
      users: GameUsers,
      pref: Option[Pref],
      me: Option[UserId],
      tv: Option[OnTv],
      flags: ExportOptions
  ) =
    getSocketStatus(pov.game).map: socket =>
      import pov.*
      Json
        .obj(
          "game" -> gameJsonView
            .baseWithDenorm(game)
            .add("moveCentis" -> (flags.movetimes.so(game.moveTimes.map(_.map(_.centis))))),
          "clock" -> game.clock.map(clockJson),
          "correspondence" -> game.correspondenceClock,
          "player" -> {
            commonWatcherJson(game, player, users(pov.color), flags) ++ Json
              .obj(
                "version" -> socket.version,
                "spectator" -> true
              )
          }.add("onGame" -> (player.isAi || socket.onGame(player.color))),
          "opponent" -> commonWatcherJson(game, opponent, users(pov.opponent.color), flags).add(
            "onGame" -> (opponent.isAi || socket.onGame(opponent.color))
          ),
          "orientation" -> pov.color.name,
          "pref" -> pref.map: pref =>
            Json
              .obj(
                "animationDuration" -> animationMillis(pov, pref),
                "coords" -> pref.coords,
                "resizeHandle" -> pref.resizeHandle,
                "replay" -> pref.replay,
                "clockTenths" -> pref.clockTenths
              )
              .add("is3d" -> pref.is3d)
              .add("clockBar" -> pref.clockBar)
              .add("highlight" -> pref.highlight)

        )
        .add("tv" -> tv.collect { case OnTv.Lichess(channel, flip) =>
          Json.obj("channel" -> channel, "flip" -> flip)
        })
        .add("userTv" -> tv.collect { case OnTv.User(userId) =>
          Json.obj("id" -> userId)
        })

  def submitMovePref(pref: Pref, game: Game, nvui: Boolean) =
    import Pref.SubmitMove.*
    pref.submitMove match
      case _ if game.hasAi || nvui => false
      case n if (n & UNLIMITED) != 0 && game.isUnlimited => true
      case n if (n & CORRESPONDENCE) != 0 && game.hasCorrespondenceClock => true
      case n if (n & CLASSICAL) != 0 && game.isSpeed(Speed.Classical) => true
      case n if (n & RAPID) != 0 && game.isSpeed(Speed.Rapid) => true
      case n if (n & BLITZ) != 0 && game.isSpeed(Speed.Blitz) => true
      case _ => false

  private def blurs(game: Game, player: GamePlayer) =
    import lila.game.Blurs.nonEmpty
    import lila.game.GameExt.playerBlurPercent
    player.blurs.nonEmpty.option:
      Json.toJsObject(player.blurs) +
        ("percent" -> JsNumber(game.playerBlurPercent(player.color)))

  private val moretimeJson = ("moretime" -> JsNumber(lila.core.round.Moretime.defaultDuration.toSeconds))
  private[round] def clockJson(clock: Clock): JsObject =
    Json.toJsObject(clock) + moretimeJson

  private def animationMillis(pov: Pov, pref: Pref) =
    pref.animationMillis * {
      if pov.game.finished then 1
      else math.max(0, math.min(1.2, ((estimateTotalTime(pov.game) - 60) / 60) * 0.2))
    }

  private def estimateTotalTime(g: Game) =
    g.clock.map(_.estimateTotalSeconds).orElse(g.correspondenceClock.map(_.estimateTotalTime)).getOrElse(1200)
