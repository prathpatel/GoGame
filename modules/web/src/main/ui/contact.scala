package lila.web
package ui

import lila.core.i18n.{ I18nKey as trans, Translate }
import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

object contact:

  def contactEmailLink(email: String)(using Translate) =
    bits.contactEmailLinkEmpty(email)(trans.site.clickToRevealEmailAddress())

  def apply(contactEmail: EmailAddress)(using Translate): Frag =
    frag(
      h1(cls := "box__top")(trans.contact.contact()),
      div(cls := "body")(
        h2("Account problems"),
        ul(
          li(a(href := routes.Auth.passwordReset)("I forgot my password")),
          li(a(href := routes.Account.reopen)("I want to reopen my account"))
        ),
        h2("Bugs and ideas"),
        p(
          "Report problems and suggest features on ",
          a(href := s"$sourceUrl/issues", targetBlank)("GitHub"),
          ". Please include the game link when reporting a problem with a game."
        ),
        h2("Anything else"),
        p("Write to ", contactEmailLink(contactEmail.value), ".")
      )
    )
