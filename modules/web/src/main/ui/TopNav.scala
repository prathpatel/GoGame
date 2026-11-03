package lila.web
package ui

import scalalib.model.Days

import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

final class TopNav(helpers: Helpers):
  import helpers.{ *, given }

  private def linkTitle(url: String, name: Frag)(using ctx: Context) =
    if ctx.blind then h3(name) else a(href := url)(name)

  def apply(seesClassMenu: Boolean, hasDgt: Boolean)(using ctx: Context) =
    st.nav(id := "topnav", cls := "hover")(
      st.section(
        linkTitle(
          "/",
          frag(
            span(cls := "play")(trans.site.play()),
            span(cls := "home")("lichess.org")
          )
        ),
        div(role := "group")(
          if ctx.noBot then a(href := s"${langHref("/")}?any#hook")(trans.site.createLobbyGame())
          else a(href := "/?any#friend")(trans.site.challengeAFriend()),
          Option.when(ctx.noBot):
            frag(
              a(href := langHref(lila.ui.deadLink))(trans.arena.arenaTournaments()),
              a(href := langHref(lila.ui.deadLink))(trans.swiss.swissTournaments()),
              a(href := langHref(lila.ui.deadLink))(trans.site.simultaneousExhibitions()),
              hasDgt.option(a(href := lila.ui.deadLink)(trans.dgt.dgtBoard())),
              (ctx.kid.no && !ctx.me.exists(_.isPatron)).option:
                a(cls := "community-patron mobile-only", href := lila.ui.deadLink)(trans.patron.donate())
            )
        )
      ),
      Option.when(ctx.noBot):
        val puzzleUrl = langHref(lila.ui.deadLink.url)
        st.section(
          linkTitle(puzzleUrl, trans.site.puzzles()),
          div(role := "group")(
            a(href := puzzleUrl)(trans.site.puzzles()),
            a(href := langHref(lila.ui.deadLink))(trans.puzzle.puzzleThemes()),
            a(href := lila.ui.deadLink)(trans.puzzle.puzzleDashboard()),
            a(href := langHref(lila.ui.deadLink))("Puzzle Streak"),
            a(href := langHref(lila.ui.deadLink))("Puzzle Storm"),
            a(href := langHref(lila.ui.deadLink))("Puzzle Racer")
          )
        )
      ,
      st.section(
        linkTitle(lila.ui.deadLink.url, trans.site.learnMenu()),
        div(role := "group")(
          Option.when(ctx.noBot):
            frag(
              a(href := langHref(lila.ui.deadLink))(trans.site.chessBasics()),
              a(href := langHref(lila.ui.deadLink))(trans.site.practice()),
              a(href := langHref(lila.ui.deadLink))(trans.coordinates.coordinates())
            )
          ,
          a(href := langHref(lila.ui.deadLink))(trans.site.studyMenu()),
          ctx.kid.no.option(a(href := langHref(lila.ui.deadLink))(trans.site.coaches())),
          seesClassMenu.option(a(href := lila.ui.deadLink)(trans.clas.lichessClasses()))
        )
      ),
      st.section:
        val broadcastUrl = langHref(lila.ui.deadLink)
        frag(
          linkTitle(broadcastUrl, trans.site.watch()),
          div(role := "group")(
            a(href := lila.ui.deadLink)(trans.broadcast.broadcasts()),
            a(href := langHref(lila.ui.deadLink))("Lichess TV"),
            a(href := lila.ui.deadLink)(trans.site.currentGames()),
            (ctx.kid.no && ctx.noBot).option(a(href := lila.ui.deadLink)(trans.site.streamersMenu())),
            ctx.noBot.option(a(href := langHref(lila.ui.deadLink))(trans.site.videoLibrary()))
          )
        )
      ,
      st.section(
        linkTitle(routes.User.list.url, trans.site.community()),
        div(role := "group")(
          a(href := routes.User.list)(trans.site.players()),
          ctx.me.map(me => a(href := routes.Relation.following(me.username))(trans.site.friends())),
          a(href := lila.ui.deadLink)(trans.team.teams()),
          ctx.kid.no.option(a(href := lila.ui.deadLink)(trans.site.forum())),
          ctx.kid.no.option(a(href := langHref(lila.ui.deadLink))(trans.site.blog())),
          (ctx.kid.no && ctx.me.exists(_.isPatron))
            .option(a(cls := "community-patron", href := lila.ui.deadLink)(trans.patron.donate()))
        )
      ),
      st.section(
        linkTitle(lila.ui.deadLink.url, trans.site.tools()),
        div(role := "group")(
          a(href := lila.ui.deadLink)(trans.site.analysis()),
          a(href := lila.ui.deadLink)(trans.site.openings()),
          a(href := lila.ui.deadLink)(trans.site.boardEditor()),
          a(href := lila.ui.deadLink)(trans.site.importGame()),
          a(href := lila.ui.deadLink)(trans.search.advancedSearch())
        )
      )
    )
