package lila.game

import chess.rating.IntRatingDiff
import chess.{ Centis, Clock as ChessClock, Color, Ply, Status }
import goban.Position
import play.api.libs.json.*

import lila.common.Json.given
import lila.core.game.{ Event, Game }

import JsonView.given

object Event:

  sealed trait Empty extends Event:
    def data = JsNull

  object Start extends Empty:
    def typ = "start"

  /** A stone was played, or a player passed. */
  case class Move(
      key: String,
      human: String,
      board: String,
      captured: List[Int],
      prisoners: chess.ByColor[Int],
      ko: Option[Int],
      scoring: Boolean,
      state: State,
      clock: Option[ClockEvent]
  ) extends Event:
    def typ = "move"
    def data = Json
      .obj(
        "uci" -> key,
        "san" -> human,
        "board" -> board,
        "ply" -> state.turns,
        "prisoners" -> Json.obj("black" -> prisoners.black, "white" -> prisoners.white)
      )
      .add("captured" -> captured.nonEmpty.option(captured))
      .add("ko" -> ko)
      .add("scoring" -> scoring)
      .add("clock" -> clock.map(_.data))
      .add("status" -> state.status)
      .add("winner" -> state.winner)
    override def moveBy = Some(!state.turns.turn)

  object Move:
    def apply(move: goban.Move, position: Position, state: State, clock: Option[ClockEvent]): Move =
      val n = position.board.width
      Move(
        key = move.key(n),
        human = move.human(n),
        board = position.board.serialize,
        captured = position.lastCaptures.toList.map(_.index).sorted,
        prisoners = position.prisoners,
        ko = position.koPoint.map(_.index),
        scoring = position.bothPassed,
        state = state,
        clock = clock
      )

  /** Dead stone marks changed during the scoring phase. */
  case class Scoring(dead: Set[goban.Point], accepted: Set[Color], score: goban.Score) extends Event:
    def typ = "scoring"
    def data = Json.obj(
      "dead" -> dead.toList.map(_.index).sorted,
      "accepted" -> accepted.toList.map(_.name),
      "score" -> JsonView.scoreJson(score)
    )

  case class RedirectOwner(
      color: Color,
      id: GameFullId,
      cookie: Option[JsObject]
  ) extends Event:
    def typ = "redirect"
    def data =
      Json
        .obj(
          "id" -> id,
          "url" -> s"/$id"
        )
        .add("cookie" -> cookie)
    override def only = Some(color)

  case class PlayerMessage(data: JsObject) extends Event:
    def typ = "message"
    override def owner = true
    override def troll = false

  case class UserMessage(data: JsObject, override val troll: Boolean, w: Boolean) extends Event:
    def typ = "message"
    override def watcher = w
    override def owner = !w

  case class EndData(game: Game, ratingDiff: Option[chess.ByColor[IntRatingDiff]]) extends Event:
    def typ = "endData"
    def data =
      Json
        .obj(
          "winner" -> game.winnerColor,
          "status" -> game.status
        )
        .add("abortedBy" -> game.abortedBy)
        .add("clock" -> game.clock.map: c =>
          Json.obj(
            "wc" -> c.remainingTime(Color.White).centis,
            "bc" -> c.remainingTime(Color.Black).centis
          ))
        .add("ratingDiff" -> ratingDiff.map: rds =>
          Json.obj(
            Color.White.name -> rds.white,
            Color.Black.name -> rds.black
          ))
        .add("boosted" -> game.boosted)
        .add("score" -> (game.status == Status.VariantEnd || game.status == Status.Draw).option(
          JsonView.scoreJson(game.score)
        ))

  case object Reload extends Empty:
    def typ = "reload"
  case object ReloadOwner extends Empty:
    def typ = "reload"
    override def owner = true

  private def reloadOr[A: Writes](typ: String, data: A) = Json.obj("t" -> typ, "d" -> data)

  // use t:reload for mobile app BC,
  // but send extra data for the web to avoid reloading
  case class RematchOffer(by: Option[Color]) extends Event:
    def typ = "reload"
    def data = reloadOr("rematchOffer", by)
    override def owner = true

  case class RematchTaken(nextId: GameId) extends Event:
    def typ = "reload"
    def data = reloadOr("rematchTaken", nextId)

  case class DrawOffer(by: Option[Color]) extends Event:
    def typ = "reload"
    def data = reloadOr("drawOffer", by)

  case class ClockInc(color: Color, time: Centis, newClock: ChessClock) extends Event:
    def typ = "clockInc"
    def data =
      Json.obj(
        "color" -> color,
        "time" -> time.centis,
        "total" -> newClock.remainingTime(color).centis
      )

  sealed trait ClockEvent extends Event

  case class Clock(white: Centis, black: Centis, nextLagComp: Option[Centis] = None) extends ClockEvent:
    def typ = "clock"
    def data =
      Json
        .obj(
          "white" -> white.toSeconds,
          "black" -> black.toSeconds
        )
        .add("lag" -> nextLagComp.filter(_ > Centis(1)))
  object Clock:
    def apply(clock: ChessClock): Clock =
      Clock(
        clock.remainingTime(Color.White),
        clock.remainingTime(Color.Black),
        clock.lagCompEstimate(clock.color)
      )

  case class Berserk(color: Color) extends Event:
    def typ = "berserk"
    def data = Json.toJson(color)

  case class CorrespondenceClock(white: Float, black: Float) extends ClockEvent:
    def typ = "cclock"
    def data = Json.obj("white" -> white, "black" -> black)
  object CorrespondenceClock:
    def apply(clock: chess.CorrespondenceClock): CorrespondenceClock =
      CorrespondenceClock(clock.whiteTime, clock.blackTime)

  case class CheckCount(white: Int, black: Int) extends Event:
    def typ = "checkCount"
    def data =
      Json.obj(
        "white" -> white,
        "black" -> black
      )

  case class State(
      turns: Ply,
      status: Option[Status],
      winner: Option[Color],
      whiteOffersDraw: Boolean,
      blackOffersDraw: Boolean
  ) extends Event:
    def typ = "state"
    def data =
      Json
        .obj(
          "color" -> turns.turn,
          "turns" -> turns
        )
        .add("status" -> status)
        .add("winner" -> winner)
        .add("wDraw" -> whiteOffersDraw)
        .add("bDraw" -> blackOffersDraw)

  case class TakebackOffers(
      white: Boolean,
      black: Boolean
  ) extends Event:
    def typ = "takebackOffers"
    def data =
      Json
        .obj()
        .add("white" -> white)
        .add("black" -> black)
    override def owner = true

  case class Crowd(
      white: Boolean,
      black: Boolean,
      watchers: Option[JsValue]
  ) extends Event:
    def typ = "crowd"
    def data =
      Json
        .obj(
          "white" -> white,
          "black" -> black
        )
        .add("watchers" -> watchers)
