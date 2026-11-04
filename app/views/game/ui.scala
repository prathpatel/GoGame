package views.game

import lila.app.UiEnv.{ *, given }

val ui = lila.game.ui.GameUi(helpers)
export ui.mini

/** /games: the games being played now, as live mini boards */
def nowPlaying(games: List[Game])(using Context) =
  Page("Games in play")
    .css("bits.games"):
      main(cls := "box box-pad games-now")(
        h1(cls := "box__top")("Games in play"),
        if games.isEmpty then
          p(cls := "games-now__empty")(
            "No games are being played right now. ",
            a(href := routes.Lobby.home)("Start one"),
            "!"
          )
        else div(cls := "now-playing games-now__list")(mini.many(games))
      )

def sides(
    pov: Pov,
    cross: Option[lila.game.Crosstable.WithMatchup],
    userTv: Option[User] = None,
    bookmarked: Boolean
)(using ctx: Context) =
  div(
    side.meta(pov, userTv, bookmarked = bookmarked),
    cross.map: c =>
      div(cls := "crosstable")(ui.crosstable(ctx.userId.foldLeft(c)(_.fromPov(_)), pov.gameId.some))
  )

def widgets(
    games: Seq[Game],
    user: Option[User] = None,
    ownerLink: Boolean = false
)(using ctx: lila.ui.Context): Frag =
  games.map: g =>
    ui.widgets(g, user = user, ownerLink = ownerLink)(none)
