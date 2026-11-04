package lila.web
package ui

import lila.core.id.CmsPageKey
import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

final class SitePages(helpers: Helpers):
  import helpers.{ *, given }

  def SitePage(title: String, active: String, contentCls: String = "")(using Context): Page =
    Page(title).wrap: body =>
      main(cls := "page-menu")(
        menu(active),
        div(cls := s"page-menu__content $contentCls")(body)
      )

  def menu(active: String)(using Translate) =
    val sep = div(cls := "sep")
    val external = frag(" ", iconTag(Icon.ExternalArrow))
    def activeCls(c: String) = cls := active.activeO(c)
    lila.ui.bits.pageMenuSubnav(
      a(activeCls("about"), href := "/about")(trans.site.about()),
      a(activeCls("faq"), href := routes.Main.faq)(trans.faq.faqAbbreviation()),
      a(activeCls("contact"), href := routes.Main.contact)(trans.contact.contact()),
      a(activeCls("terms"), href := routes.Main.terms)(trans.site.termsOfService()),
      a(activeCls("privacy"), href := routes.Main.privacy)(trans.site.privacy()),
      sep,
      a(activeCls("source"), href := sourceUrl, targetBlank)(trans.site.sourceCode(), external),
      a(activeCls("lag"), href := routes.Main.lag)("Is the server lagging?")
    )

  private val repoRoot = sourceUrl

  def source(pageTitle: String, rendered: Frag, version: Option[WebConfig.LilaVersion])(using
      Context
  ) =
    SitePage(title = pageTitle, active = "source", contentCls = "page force-ltr")
      .css("bits.source")
      .js(esmInitBit("setAssetInfo")):
        frag(
          st.section(cls := "box")(
            h1(cls := "box__top")(pageTitle),
            table(cls := "slist slist-pad", id := "version")(
              thead(
                tr(
                  th("Current versions"),
                  th(colspan := 2)("Last boot: ", momentFromNow(lila.common.Uptime.startedAt))
                )
              ),
              tbody(
                version.map: v =>
                  tr(
                    td(
                      span("Server"),
                      timeTag(v.date)
                    ),
                    td(span(a(href := s"$repoRoot/commits/${v.commit}"):
                      pre(v.commit.take(7)))),
                    td(
                      a(href := s"$repoRoot/compare/${v.commit}...master", title := "Upcoming changes")(
                        pre("...")
                      )
                    )
                  ),
                tr(
                  td(
                    "Assets",
                    timeTag(id := "asset-version-date")
                  ),
                  td(a(id := "asset-version-commit")(pre)),
                  td(a(id := "asset-version-upcoming", title := "Upcoming changes")(pre("...")))
                )
              )
            )
          ),
          br,
          st.section(cls := "box box-pad body")(rendered)
        )

  def lag(using Context) =
    import trans.lag as trl
    SitePage(title = "Is the server lagging?", active = "lag")
      .css("bits.lag")
      .js(esmInit("chart.lag")):
        div(cls := "box box-pad lag")(
          h1(cls := "box__top")(
            trl.isLichessLagging(),
            span(cls := "answer short")(
              span(cls := "waiting")(trl.measurementInProgressThreeDot()),
              span(cls := "nope-nope none")(trl.noAndYourNetworkIsGood()),
              span(cls := "nope-yep none")(trl.noAndYourNetworkIsBad()),
              span(cls := "yep none")(trl.yesItWillBeFixedSoon())
            )
          ),
          div(cls := "answer long")(
            trl.andNowTheLongAnswerLagComposedOfTwoValues()
          ),
          div(cls := "sections")(
            st.section(cls := "server")(
              h2(trl.lichessServerLatency()),
              div(cls := "meter")(canvas(cls := "server-chart")),
              p(
                trl.serverLatencyAndLoadExplanation()
              )
            ),
            st.section(cls := "network")(
              h2(trl.networkBetweenLichessAndYou()),
              div(cls := "meter")(canvas(cls := "network-chart")),
              p(
                trl.networkBetweenLichessAndYouExplanation()
              )
            )
          ),
          div(cls := "last-word")(
            p(trl.youCanFindTheseValuesAtAnyTimeByClickingOnYourUsername()),
            h2(trl.lagCompensation()),
            p(trl.lagCompensationExplanation())
          )
        )

  def errorPage =
    Page("Internal server error"):
      main(cls := "page-small box box-pad")(
        h1(cls := "box__top")("Something went wrong on this page"),
        p(
          "If the problem persists, please ",
          a(href := s"${routes.Main.contact}#help-error-page")("report the bug"),
          "."
        )
      )
