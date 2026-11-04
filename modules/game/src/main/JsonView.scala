package lila.game

import chess.{ Clock, Color }
import play.api.libs.json.*

import lila.common.Json.{ *, given }
import lila.core.LightUser
import lila.core.game.{ Blurs, Game, Player, Pov, Source }
import lila.game.GameExt.{ expirable, timeForFirstMove }

final class JsonView(rematches: Rematches):

  import JsonView.given

  def immutable(game: Game) =
    Json
      .obj(
        "id" -> game.id,
        "variant" -> game.variant,
        "speed" -> game.speed.key,
        "perf" -> game.perfKey,
        "rated" -> game.rated,
        "source" -> game.source,
        "createdAt" -> game.createdAt
      )
      .add("startedAtTurn" -> game.go.startedAtPly.some.filter(_ > 0))
      .add("tournamentId" -> game.tournamentId)
      .add("swissId" -> game.swissId)
      .add("rules" -> game.metadata.nonEmptyRules)

  def base(game: Game) =
    immutable(game) ++ Json
      .obj(
        "go" -> JsonView.goJson(game),
        "turns" -> game.ply,
        "status" -> game.status
      )
      .add("winner" -> game.winnerColor)
      .add("abortedBy" -> game.abortedBy)
      .add("rematch" -> rematches.getAcceptedId(game.id))
      // a rated game that ended in very few moves: no rematch (see Rematcher.couldRematch)
      .add("boosted" -> game.boosted)
      .add("drawOffers" -> (!game.drawOffers.isEmpty).option(game.drawOffers.normalizedPlies))

  // adds fields that should be computed by the client instead
  def baseWithDenorm(game: Game) =
    base(game) ++ Json
      .obj("player" -> game.turnColor)
      .add("lastMove" -> game.lastMoveKey)

  def apiAiNewGame(pov: Pov): JsObject =
    baseWithDenorm(pov.game) ++ Json.obj("fullId" -> pov.fullId)

  def ownerPreview(pov: Pov)(using LightUser.GetterSync) =
    Json
      .obj(
        "fullId" -> pov.fullId,
        "gameId" -> pov.gameId,
        "board" -> maybeBoard(pov),
        "size" -> pov.game.boardSize.size,
        "color" -> pov.color,
        "lastMove" -> (pov.game.lastMoveKey | ""),
        "source" -> pov.game.source,
        "status" -> pov.game.status,
        "variant" -> Json.obj(
          "key" -> pov.game.variant.key,
          "name" -> pov.game.variant.name
        ),
        "speed" -> pov.game.speed.key,
        "perf" -> pov.game.perfKey,
        "rated" -> pov.game.rated,
        "hasMoved" -> pov.hasMoved,
        "opponent" -> Json
          .obj(
            "id" -> pov.opponent.userId,
            "username" -> lila.game.Namer
              .playerTextBlocking(pov.opponent, withRating = false)
          )
          .add("rating" -> pov.opponent.rating)
          .add("ratingDiff" -> pov.opponent.ratingDiff)
          .add("ai" -> pov.opponent.aiLevel),
        "isMyTurn" -> pov.isMyTurn
      )
      .add("secondsLeft" -> pov.remainingSeconds)
      .add("tournamentId" -> pov.game.tournamentId)
      .add("swissId" -> pov.game.swissId)
      // .add("orientation" -> pov.game.variant.racingKings.option(chess.White))
      .add("winner" -> pov.game.winnerColor)
      .add("rating" -> pov.player.rating)
      .add("ratingDiff" -> pov.player.ratingDiff)

  def maybeBoard(pov: Pov): String =
    if pov.player.blindfold then "." * pov.game.boardSize.points else pov.game.board.serialize

  def player(p: Player, user: Option[LightUser]) =
    Json
      .obj()
      .add("user", user)
      .add("rating", p.rating)
      .add("ratingDiff", p.ratingDiff)
      .add("name", p.name)
      .add("provisional" -> p.provisional)
      .add("aiLevel" -> p.aiLevel)
      .add("blindfold" -> p.blindfold)

object JsonView:

  def scoreJson(score: goban.Score) =
    Json.obj(
      "black" -> score.black,
      "white" -> score.white,
      "komi" -> score.komi.value,
      "territory" -> Json.obj("black" -> score.territory.black, "white" -> score.territory.white),
      "stones" -> Json.obj("black" -> score.stones.black, "white" -> score.stones.white),
      "result" -> score.result,
      "ownership" -> ownership(score)
    )

  private def ownership(score: goban.Score): JsArray =
    JsArray(score.owner.toList.sortBy(_._1.index).map((p, c) => Json.arr(p.index, if c.black then 1 else 2)))

  /** Everything a client needs to draw the board. */
  def goJson(game: Game): JsObject =
    val size = game.boardSize.size
    Json
      .obj(
        "size" -> size,
        "komi" -> game.komi.value,
        "board" -> game.board.serialize,
        "moves" -> game.go.moveKeys,
        "prisoners" -> Json.obj("black" -> game.go.prisoners.black, "white" -> game.go.prisoners.white)
      )
      .add("handicap" -> (game.handicap > 0).option(game.handicap))
      .add("lastMove" -> game.lastMoveKey)
      .add("ko" -> game.position.koPoint.map(_.index))
      // also for a game finished by counting (a win is VariantEnd, a jigo is Draw), dead stones or not
      .add("scoring" -> (game.inScoringPhase || game.scoring.dead.nonEmpty ||
        game.status == chess.Status.VariantEnd || game.status == chess.Status.Draw).option:
        Json.obj(
          "dead" -> game.scoring.dead.toList.map(_.index).sorted,
          "accepted" -> game.scoring.accepted.toList.map(_.name),
          "score" -> scoreJson(game.score)
        ))

  def expiration(game: Game) =
    game.expirable.option:
      Json.obj(
        "idleMillis" -> (nowMillis - game.movedAt.toMillis),
        "millisToMove" -> game.timeForFirstMove.millis
      )

  given OWrites[chess.Status] = OWrites: s =>
    Json.obj(
      "id" -> s.id,
      "name" -> s.name
    )

  given OWrites[Crosstable.Result] = Json.writes

  given OWrites[Crosstable.Users] = OWrites: users =>
    JsObject(users.toList.map: u =>
      u.id.value -> JsNumber(u.score / 10d))

  given OWrites[Crosstable] = OWrites: c =>
    Json.obj(
      "users" -> c.users,
      "nbGames" -> c.nbGames
      // "results" -> c.results
    )

  given OWrites[Crosstable.Matchup] = OWrites: m =>
    Json.obj(
      "users" -> m.users,
      "nbGames" -> m.users.nbGames
    )

  given OWrites[Crosstable.WithMatchup] = OWrites: c =>
    Json.toJsObject(c.crosstable).add("matchup" -> c.matchup)

  given OWrites[Blurs] = OWrites: blurs =>
    import lila.game.Blurs.binaryString
    Json.obj(
      "nb" -> blurs.nb,
      "bits" -> blurs.binaryString
    )

  given OWrites[chess.variant.Variant] = OWrites: v =>
    Json.obj(
      "key" -> v.key,
      "name" -> v.name,
      "short" -> v.shortName
    )

  given OWrites[Clock] = OWrites: c =>
    Json.obj(
      "running" -> c.isRunning,
      "initial" -> c.limitSeconds,
      "increment" -> c.incrementSeconds,
      "white" -> c.remainingTime(Color.White).toSeconds,
      "black" -> c.remainingTime(Color.Black).toSeconds,
      "emerg" -> c.config.emergSeconds
    )

  given Writes[Source] = writeAs(_.name)
  given Writes[lila.core.game.GameRule] = writeAs(_.toString)
