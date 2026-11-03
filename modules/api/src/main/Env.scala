package lila.api

import com.softwaremill.macwire.*
import org.apache.pekko.actor.*
import play.api.Mode

import lila.chat.{ GetLinkCheck, IsChatFresh }
import lila.common.Bus

@Module
final class Env(
    net: lila.core.config.NetConfig,
    securityEnv: lila.security.Env,
    mailerEnv: lila.mailer.Env,
    gameEnv: lila.game.Env,
    chatEnv: lila.chat.Env,
    roundEnv: lila.round.Env,
    bookmarkApi: lila.bookmark.BookmarkApi,
    prefApi: lila.pref.PrefApi,
    playBanApi: lila.playban.PlaybanApi,
    userEnv: lila.user.Env,
    relationEnv: lila.relation.Env,
    lobbyEnv: lila.lobby.Env,
    challengeEnv: lila.challenge.Env,
    socketEnv: lila.socket.Env,
    msgEnv: lila.msg.Env,
    reportEnv: lila.report.Env,
    shutupEnv: lila.shutup.Env,
    picfitUrl: lila.memo.PicfitUrl,
    cacheApi: lila.memo.CacheApi,
    webConfig: lila.web.WebConfig,
    manifest: lila.web.AssetManifest,
    tokenApi: lila.oauth.AccessTokenApi,
    playerCounter: lila.web.PlayerCounter
)(using scheduler: Scheduler)(using
    Mode,
    Executor,
    ActorSystem,
    org.apache.pekko.stream.Materializer,
    lila.core.i18n.Translator
):

  export net.{ baseUrl, domain }

  lazy val userApi = wire[UserApi]

  export webConfig.apiToken

  lazy val roundApi = wire[RoundApi]

  lazy val lobbyApi = wire[LobbyApi]

  lazy val accountTermination = wire[AccountTermination]

  lazy val cli = wire[Cli]

  private lazy val linkCheck = wire[LinkCheck]
  lazy val chatFreshness = wire[ChatFreshness]

  Bus.sub[GetLinkCheck]:
    case GetLinkCheck(line, source, promise) =>
      promise.completeWith(linkCheck(line, source))
  Bus.sub[IsChatFresh]:
    case IsChatFresh(source, promise) =>
      promise.completeWith(chatFreshness.of(source))
  Bus.sub[lila.core.security.GarbageCollect]: gc =>
    accountTermination.garbageCollect(gc.userId)
  Bus.sub[lila.core.playban.RageSitClose]: close =>
    accountTermination.lichessDisable(close.userId)

  lila.i18n.Registry.asyncLoadLanguages()

  scheduler.scheduleWithFixedDelay(1.minute, 1.minute): () =>
    lila.mon.bus.classifiers.update(lila.common.Bus.size())
    lila.mon.jvm.threads()
    // ensure the Lichess user is online
    socketEnv.remoteSocket.onlineUserIds.getAndUpdate(_ + UserId.lichess)
    userEnv.repo.setSeenAt(UserId.lichess)
