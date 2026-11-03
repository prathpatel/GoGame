package views.game
package side

import lila.app.UiEnv.{ *, given }
import lila.game.GameExt.perfType

private val separator = " • "
private val dataUserTv = attr("data-user-tv")

def apply(
    pov: Pov,
    userTv: Option[User] = None,
    bookmarked: Boolean
)(using ctx: Context): Option[Frag] =
  meta(pov, userTv, bookmarked)

def meta(
    pov: Pov,
    userTv: Option[User] = None,
    bookmarked: Boolean
)(using ctx: Context): Option[Frag] =
  ctx.noBlind.option:
    import pov.*
    div(cls := "game__meta")(
      st.section(
        div(cls := "game__meta__infos", dataIcon := ui.gameIcon(game))(
          div(cls := "header")(
            div(cls := "setup")(
              views.bookmark.toggle(game, bookmarked),
              ui.widgets.showClock(game),
              separator,
              ratedName(game.rated),
              separator,
              span(title := game.perfType.desc)(game.perfType.trans)
            ),
            pastMomentWithPreload(game.createdAt)
          ),
          div(cls := "go-setup")(
            game.boardSize.name,
            separator,
            s"Komi ${goban.Score.fmt(game.komi.value)}",
            (game.handicap > 0).option(frag(separator, s"Handicap ${game.handicap}"))
          )
        ),
        div(cls := "game__meta__players")(
          game.players.mapList: p =>
            div(cls := s"player color-icon is ${p.color.name} text")(
              playerLink(
                p,
                withOnline = false,
                withDiff = true,
                withBerserk = true
              )
            )
        )
      ),
      game.finishedOrAborted.option(
        st.section(cls := "status")(
          ui.gameEndStatus(game),
          game.winner.map: winner =>
            frag(
              separator,
              if winner.color.black then "Black is victorious" else "White is victorious"
            )
        )
      ),
      game.finished.option(
        st.section(cls := "game__sgf")(
          a(
            cls := "text",
            dataIcon := Icon.Download,
            href := routes.Game.exportOne(game.id),
            attr("download") := true
          )("Download SGF")
        )
      ),
      userTv.map: u =>
        st.section(cls := "game__tv"):
          h2(cls := "top user-tv text", dataUserTv := u.id, dataIcon := Icon.AnalogTv)(u.titleUsername)
    )
