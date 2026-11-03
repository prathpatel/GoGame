package lila.round
package ui

import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

final class RoundUi(helpers: Helpers, gameUi: lila.game.ui.GameUi):
  import helpers.{ *, given }

  def RoundPage(title: String)(using ctx: Context) =
    Page(title)
      .css("round")
      .flag(_.zoom)

  def povOpenGraph(pov: Pov)(using Translate) =
    OpenGraph(
      image = none,
      title = titleGame(pov.game),
      url = routeUrl(routes.Round.watcher(pov.gameId, pov.color)),
      description = describePov(pov)
    )

  def others(playing: UrgentGames, simul: Option[Frag])(using Context) =
    val switchId = "round-toggle-autoswitch"
    frag(
      h3(
        simul | frag(trans.site.currentGames()),
        form3.cmnToggleWrap(st.title := trans.site.automaticallyProceedToNextGameAfterMoving.txt())(
          trans.site.autoSwitch(),
          form3.cmnToggle(switchId, switchId, checked = false)
        )
      ),
      div(cls := "now-playing"):
        val (myTurn, otherTurn) = playing.value.partition(_.isMyTurn)
        (myTurn ++ otherTurn.take(8 - myTurn.size))
          .take(12)
          .map: pov =>
            a(href := routes.Round.player(pov.fullId), cls := pov.isMyTurn.option("my_turn"))(
              span(
                cls := s"mini-game mini-game--init goban-${pov.game.boardSize.size}",
                gameUi.mini.renderState(pov)
              )(
                span(cls := "goban-wrap")(
                  lila.game.ui.GobanSvg(pov.game.board.serialize, pov.game.boardSize.size, pov.game.lastMoveKey)
                )
              ),
              span(cls := "meta")(
                playerUsername(
                  pov.opponent.light,
                  pov.opponent.userId.flatMap(lightUserSync),
                  withRating = false,
                  withTitle = true
                ),
                span(cls := "indicator")(
                  if pov.isMyTurn then
                    pov.remainingSeconds
                      .fold[Frag](trans.site.yourTurn())(secondsFromNow(_, alwaysRelative = true))
                  else nbsp
                )
              )
            )
    )

  def describePov(pov: Pov)(using Translate) =
    import pov.*
    val p1 = playerText(game.whitePlayer, withRating = true)
    val p2 = playerText(game.blackPlayer, withRating = true)
    val plays = if game.finishedOrAborted then "played" else "is playing"
    val speedAndClock =
      if game.sourceIs(_.Import) then "imported"
      else
        game.clock.fold(chess.Speed.Correspondence.name): c =>
          s"${chess.Speed(c.config).name} (${c.config.show})"

    val rated = game.rated.name
    val variant = s"${game.boardSize.name} Go"
    import chess.Status.*
    val result = (game.winner, game.loser, game.status) match
      case (Some(w), _, VariantEnd) => s"${playerText(w)} won by ${game.score.result}"
      case (_, _, Aborted | NoStart) => gameUi.abortReason(game).txt()
      case (_, Some(l), Resign | Timeout | Cheat | NoStart) => s"${playerText(l)} resigned"
      case (_, Some(l), Outoftime) => s"${playerText(l)} ran out of time"
      case (Some(w), _, UnknownFinish) => s"${playerText(w)} won"
      case (_, _, Draw | UnknownFinish) => "Game is a draw (jigo)"
      case _ if game.finished => "Game ended"
      case _ => "Game is still ongoing"
    val moves = game.playedPlies.value
    s"$p1 $plays $p2 in a $rated $speedAndClock game of $variant. $result after ${pluralize("move", moves)}. Click to replay, analyse, and discuss the game!"

  def roundAppPreload(pov: Pov)(using Context): Tag =
    div(cls := "round__app")(
      div(cls := "round__app__board main-board")(
        div(cls := "goban-wrap")(
          lila.game.ui.GobanSvg(
            if pov.player.blindfold then "." * pov.game.boardSize.points else pov.game.board.serialize,
            pov.game.boardSize.size,
            pov.game.lastMoveKey
          )
        )
      ),
      div(cls := "col1-rmoves-preload")
    )
