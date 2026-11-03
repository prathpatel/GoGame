package views.game

import lila.app.UiEnv.{ *, given }

val ui = lila.game.ui.GameUi(helpers)
export ui.mini

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
