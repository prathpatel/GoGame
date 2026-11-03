package lila.ui

import scalatags.text.Builder

import lila.core.config.NetDomain
import lila.ui.ScalatagsTemplate.{ *, given }

object HtmlHelper:

  // a black and a white stone circling each other (ui/lib/css/component/_loader.scss)
  val spinner: Frag = raw:
    """<div class="spinner"><svg viewBox="-2 -2 54 54"><circle class="spinner__track" cx="25" cy="25" r="16"/><g class="spinner__orbit"><circle class="spinner__black" cx="25" cy="9" r="7.5"/><circle class="spinner__white" cx="25" cy="41" r="7.5"/></g></svg></div>"""

  def qrcode(url: Url, width: Int = 320): Tag =
    canvas(cls := "qrcode", attr("data-qr-url") := url, attr("data-width") := width)

  def titleOrText(v: String)(using ctx: Context): Modifier = titleOrTextFor(ctx.blind, v)

  def titleOrTextFor(blind: Boolean, v: String): Modifier = (t: Builder) =>
    if blind then t.addChild(StringFrag(v))
    else t.setAttr("title", Builder.GenericAttrValueSource(v))

  def copyMeLink(url: Url, name: Frag): Tag = copyMe(a(targetBlank, href := url)(name))

  def copyMeContent(url: Url, name: Frag): Tag = copyMeLink(url, name)(cls := "fetch-content")

  def copyMeInput(content: String): Tag =
    copyMe(input(spellcheck := "false", readonly, value := content))

  private def copyMe(target: Tag): Tag =
    div(cls := "copy-me")(
      target(cls := "copy-me__target"),
      button(cls := "copy-me__button button button-metal", dataIcon := Icon.Clipboard)
    )

trait HtmlHelper:

  def richText(rawText: String, nl2br: Boolean = true, expandImg: Boolean = true)(using NetDomain): Frag

  protected def isProd: Boolean

  def testId(id: String): Modifier = if isProd then emptyFrag else attr("data-testid") := id

  export HtmlHelper.*
  export scalalib.StringOps.{ shorten, urlencode, addQueryParam, addQueryParams }

/** Links to pages that don't exist in this Go fork (removed chess features) point home. */
val deadLink: play.api.mvc.Call = play.api.mvc.Call("GET", "/")
