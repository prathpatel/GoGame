package lila.app

import com.softwaremill.macwire.*
import play.api.libs.ws.StandaloneWSClient
import play.api.mvc.{ ControllerComponents, SessionCookieBaker }
import play.api.{ Configuration, Environment, Mode }

import lila.common.config.GetRelativeFile
import lila.core.config.*

final class Env(
    val config: Configuration,
    val controllerComponents: ControllerComponents,
    environment: Environment,
    shutdown: org.apache.pekko.actor.CoordinatedShutdown,
    cookieBaker: SessionCookieBaker
)(using val system: org.apache.pekko.actor.ActorSystem, val executor: Executor)(using
    StandaloneWSClient,
    org.apache.pekko.stream.Materializer
):
  val net: NetConfig = lila.web.WebConfig.netConfig(config)
  export net.baseUrl
  val routeUrl: RouteUrl = call => Url(s"${baseUrl}${call.url}")

  given mode: Mode = environment.mode
  given translator: lila.core.i18n.Translator = lila.i18n.Translator
  given scheduler: Scheduler = system.scheduler
  given RateLimit = net.rateLimit
  given NetDomain = net.domain
  val getFile: GetRelativeFile = GetRelativeFile(environment.getFile(_))

  // wire all the lila modules in the right order
  val i18n: lila.i18n.Env.type = lila.i18n.Env
  val mongo: lila.db.Env = wire[lila.db.Env]
  val memo: lila.memo.Env = wire[lila.memo.Env]
  val socket: lila.socket.Env = wire[lila.socket.Env]
  val user: lila.user.Env = wire[lila.user.Env]
  import user.given
  val mailer: lila.mailer.Env = wire[lila.mailer.Env]
  val oAuth: lila.oauth.Env = wire[lila.oauth.Env]
  val security: lila.security.Env = wire[lila.security.Env]
  val pref: lila.pref.Env = wire[lila.pref.Env]
  val relation: lila.relation.Env = wire[lila.relation.Env]
  val game: lila.game.Env = wire[lila.game.Env]
  import game.given
  val notifyM: lila.notify.Env = wire[lila.notify.Env]
  val irc: lila.irc.Env = wire[lila.irc.Env]
  val report: lila.report.Env = wire[lila.report.Env]
  val shutup: lila.shutup.Env = wire[lila.shutup.Env]
  val chat: lila.chat.Env = wire[lila.chat.Env]
  val msg: lila.msg.Env = wire[lila.msg.Env]
  val playban: lila.playban.Env = wire[lila.playban.Env]
  val history: lila.history.Env = wire[lila.history.Env]
  val bookmark: lila.bookmark.Env = wire[lila.bookmark.Env]
  val round: lila.round.Env = wire[lila.round.Env]
  val pool: lila.pool.Env = wire[lila.pool.Env]
  import pool.given
  val lobby: lila.lobby.Env = wire[lila.lobby.Env]
  val setup: lila.setup.Env = wire[lila.setup.Env]
  val challenge: lila.challenge.Env = wire[lila.challenge.Env]
  val web: lila.web.Env = wire[lila.web.Env]
  val api: lila.api.Env = wire[lila.api.Env]

  val preloader = wire[mashup.Preload]
  val socialInfo = wire[mashup.UserInfo.SocialApi]
  val userNbGames = wire[mashup.UserInfo.NbGamesApi]
  val userInfo = wire[mashup.UserInfo.UserInfoApi]
  val gamePaginator = wire[mashup.GameFilterMenu.PaginatorBuilder]
  val homeCache = wire[http.HomeCache]

end Env
