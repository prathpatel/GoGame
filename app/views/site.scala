package views.site

import lila.app.UiEnv.{ *, given }

val message = lila.web.ui.SiteMessage(helpers)
val ui = lila.web.ui.SitePages(helpers)

object page:

  private val faqUi = lila.web.ui.FaqUi(helpers, ui)(
    standardRankableDeviation = lila.rating.Glicko.standardRankableDeviation,
    variantRankableDeviation = lila.rating.Glicko.variantRankableDeviation
  )

  def faq(using Context) = faqUi.apply.js(esmInitBit("faq"))

  def contact(using Context) =
    ui.SitePage(
      title = trans.contact.contact.txt(),
      active = "contact",
      contentCls = "page box box-pad"
    ).css("bits.contact")
      .js(esmInitBit("contact"))(lila.web.ui.contact(netConfig.email))
