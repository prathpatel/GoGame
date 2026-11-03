package lila.web
package ui

import play.api.data.{ Field, Form }

import lila.core.captcha.Captcha
import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

final class CaptchaUi(helpers: Helpers):
  import helpers.{ *, given }

  private val dataCheckUrl = attr("data-check-url")
  private val dataPlayable = attr("data-playable")

  def apply(form: Form[?] | Field, captcha: Captcha)(using ctx: Context) =
    frag(
      form3.hidden(formField(form, "gameId"), captcha.gameId.some),
      if ctx.blind then form3.hidden(formField(form, "move"), captcha.solutions.head.some)
      else
        div(
          cls := List(
            "captcha form-group" -> true,
            "is-invalid" -> formErrors(form).exists(_.messages.has(lila.core.captcha.failMessage))
          ),
          dataCheckUrl := routes.Main.captchaCheck(captcha.gameId)
        )(
          div(cls := "challenge")(
            div(
              cls := "goban-captcha",
              attr("data-board") := captcha.board,
              attr("data-size") := captcha.size,
              dataPlayable := 1
            )
          ),
          div(cls := "captcha-explanation")(
            label(cls := "form-label")("Black to play: capture the white stones"),
            br,
            br,
            "This is a Go captcha: click the point where Black captures.",
            br,
            br,
            div(cls := "result success text", dataIcon := Icon.Checkmark)("Captured!"),
            div(cls := "result failure text", dataIcon := Icon.NotAllowed)("That doesn't capture, try again."),
            form3.hidden(formField(form, "move"))
          )
        )
    )

  def hiddenEmpty(form: Form[?] | Field) = frag(
    form3.hidden(formField(form, "gameId")),
    form3.hidden(formField(form, "move"))
  )

  private def formField(form: Form[?] | Field, name: String) = form match
    case f: Form[?] => f(name)
    case f: Field => f(name)
  private def formErrors(form: Form[?] | Field) = form match
    case f: Form[?] => f.errors
    case f: Field => f.errors
