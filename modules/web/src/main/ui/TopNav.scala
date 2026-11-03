package lila.web
package ui

import scalalib.model.Days

import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

final class TopNav(helpers: Helpers, siteName: String):
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
            span(cls := "home")(siteName)
          )
        ),
        div(role := "group")(
          if ctx.noBot then a(href := s"${langHref("/")}?any#hook")(trans.site.createLobbyGame())
          else a(href := "/?any#friend")(trans.site.challengeAFriend()),
          a(href := "/?any#friend")(trans.site.challengeAFriend()),
          a(href := "/?any#ai")(trans.site.playAgainstComputer())
        )
      ),
      st.section(
        linkTitle(routes.User.list.url, trans.site.community()),
        div(role := "group")(
          a(href := routes.User.list)(trans.site.players()),
          ctx.me.map(me => a(href := routes.Relation.following(me.username))(trans.site.friends()))
        )
      ),
      st.section(
        linkTitle("/faq", "Learn"),
        div(role := "group")(
          a(href := "/faq")(trans.faq.faqAbbreviation()),
          a(href := "https://en.wikipedia.org/wiki/Rules_of_Go", targetBlank)("Rules of Go")
        )
      )
    )
