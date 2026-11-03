package views.lobby

import play.api.libs.json.Json

import lila.app.UiEnv.{ *, given }
import lila.app.mashup.Preload.Homepage
import lila.core.perf.UserWithPerfs

object home:

  def apply(homepage: Homepage)(using ctx: Context) =
    import homepage.*
    Page("")
      .copy(fullTitle = s"$siteName • Play Go online".some)
      .js(
        PageModule(
          "lobby",
          Json
            .obj(
              "data" -> data,
              "showRatings" -> ctx.pref.showRatings,
              // quick pairing pools: board size and clock (modules/pool PoolList)
              "pools" -> lila.pool.PoolList.all.map: p =>
                Json.obj(
                  "id" -> p.id.value,
                  "size" -> p.size.size,
                  "lim" -> p.clock.limitInMinutes,
                  "inc" -> p.clock.incrementSeconds.value
                )
            )
            .add("hasUnreadLichessMessage", hasUnreadLichessMessage)
            .add("playban", playban.map(lila.playban.TempBan.lobbyJson))
        )
      )
      .css("lobby")
      .graph(
        OpenGraph(
          title = "Play Go online, free",
          url = netBaseUrl.into(Url),
          description = "A free, open source server for the game of Go (Baduk, Weiqi)."
        )
      )
      .hrefLangs(lila.ui.LangPath("/")):
        given Option[UserWithPerfs] = homepage.me
        main(
          cls := List(
            "lobby" -> true,
            "lobby-nope" -> (playban.isDefined || currentGame.isDefined || homepage.hasUnreadLichessMessage)
          )
        )(
          div(cls := "lobby__side")(
            div(cls := "about-side")(
              ctx.blind.option(h2(trans.site.about())),
              p(
                strong(siteName),
                " is a free and open source server for the game of Go, also called Baduk or Weiqi."
              ),
              p(
                "Play 9×9, 13×13 and 19×19 games against people or the computer. ",
                "Captures, ko, passing and area scoring work just like on a real board."
              ),
              a(href := "/about")(trans.site.aboutX(siteName), "...")
            )
          ),
          currentGame
            .map(bits.currentGameInfo)
            .orElse:
              hasUnreadLichessMessage.option(bits.showUnreadLichessMessage)
            .orElse:
              playban.map(bits.playbanInfo)
            .getOrElse:
              if ctx.blind then blindLobby(blindGames) else bits.lobbyApp
          ,
          div(cls := "lobby__table")(
            div(cls := "lobby__start")(
              button(cls := "button button-metal lobby__start__button lobby__start__button--hook")(
                trans.site.createLobbyGame()
              ),
              button(cls := "button button-metal lobby__start__button lobby__start__button--friend")(
                trans.site.challengeAFriend()
              ),
              button(cls := "button button-metal lobby__start__button lobby__start__button--ai")(
                trans.site.playAgainstComputer()
              )
            )
          ),
          div(cls := "lobby__about")(
            ctx.blind.option(h2(trans.site.about())),
            a(href := "/about")(trans.site.aboutX(siteName)),
            a(href := "/faq")(trans.faq.faqAbbreviation()),
            a(href := "/contact")(trans.contact.contact()),
            a(href := "/source")(trans.site.sourceCode())
          )
        )
