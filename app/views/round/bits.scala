package views.round

import lila.app.UiEnv.{ *, given }
import lila.game.GameExt.playerBlurPercent

lazy val ui = lila.round.ui.RoundUi(helpers, views.game.ui)

def underchat(game: Game)(using ctx: Context) =
  frag(
    views.chat.spectatorsFrag,
    isGranted(_.ViewBlurs).option(
      div(cls := "round__mod")(
        game.players.all
          .filter(p => game.playerBlurPercent(p.color) > 30)
          .map { p =>
            div(
              playerLink(
                p,
                cssClass = s"is color-icon ${p.color.name}".some,
                withOnline = false,
                mod = true
              ),
              s" ${p.blurs.nb}/${game.playerMoves(p.color)} blurs ",
              strong(game.playerBlurPercent(p.color), "%")
            )
          }
          // game.players flatMap { p => p.holdAlert.map(p ->) } map {
          //   case (p, h) => div(
          //     playerLink(p, cssClass = s"is color-icon ${p.color.name}".some, mod = true, withOnline = false),
          //     "hold alert",
          //     br,
          //     s"(ply: ${h.ply}, mean: ${h.mean} ms, SD: ${h.sd})"
          //   )
          // }
      )
    )
  )

private[round] def side(
    pov: Pov,
    userTv: Option[User] = None,
    bookmarked: Boolean
)(using Context) =
  views.game.side(pov, userTv = userTv, bookmarked = bookmarked)
