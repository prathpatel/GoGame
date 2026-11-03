package lila.game

import chess.Status

import lila.core.LightUser
import lila.core.config.RouteUrl
import lila.core.game.{ Game, Player }

/** Exports games as SGF, the standard Go record format (replaces lila's PGN export). */
final class SgfDump(routeUrl: RouteUrl, lightUserApi: lila.core.user.LightUserApi)(using Executor):

  def apply(game: Game, withRating: Boolean = true): Fu[String] =
    for
      black <- playerName(game.blackPlayer, withRating)
      white <- playerName(game.whitePlayer, withRating)
    yield goban.Sgf.write(
      game.go,
      goban.Sgf.Tags(
        black = black.some,
        white = white.some,
        blackRank = withRating.so(game.blackPlayer.rating.map(_.toString)),
        whiteRank = withRating.so(game.whitePlayer.rating.map(_.toString)),
        date = game.createdAt.date.toString.some,
        result = SgfDump.result(game),
        timeControl = game.clock.map(_.config.limitSeconds.value.toString)
      )
    )

  def filename(game: Game): String =
    s"go_${game.id}_${game.createdAt.date}.sgf"

  private def playerName(p: Player, withRating: Boolean): Fu[String] =
    p.userId
      .so(lightUserApi.async)
      .map: user =>
        user.fold(p.aiLevel.fold(p.name.fold("Anonymous")(_.value))(l => s"Computer level $l"))(_.name.value)

object SgfDump:

  /** SGF RE[] property: B+R, W+T, B+3.5, 0 (jigo), Void... */
  def result(game: Game): Option[String] =
    def c(color: chess.Color) = if color.black then "B" else "W"
    if !game.finished then none
    else
      game.status match
        case Status.Draw => "0".some
        case Status.Aborted | Status.NoStart => "Void".some
        case Status.VariantEnd => game.score.result.some
        case Status.Resign => game.winnerColor.map(w => s"${c(w)}+R")
        case Status.Outoftime | Status.Timeout => game.winnerColor.map(w => s"${c(w)}+T")
        case _ => game.winnerColor.map(w => s"${c(w)}+F")
