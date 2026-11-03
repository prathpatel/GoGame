package lila.app
package mashup

import play.api.libs.json.*

import lila.core.perf.UserWithPerfs
import lila.mon.extensions.*
import lila.playban.TempBan
import lila.round.RoundGame.isMyTurnOrJustMoved
import lila.round.UrgentGames
import lila.user.{ LightUserApi, Me }

/* Data for the home page: the lobby, current games and a few notices. */
final class Preload(
    gameRepo: lila.game.GameRepo,
    perfsRepo: lila.user.UserPerfsRepo,
    lobbyApi: lila.api.LobbyApi,
    playbanApi: lila.playban.PlaybanApi,
    lightUserApi: LightUserApi,
    roundProxy: lila.round.GameProxyRepo,
    unreadCount: lila.msg.MsgUnreadCount,
    notifyApi: lila.notify.NotifyApi
)(using Executor):

  import Preload.*

  def apply()(using ctx: Context): Fu[Homepage] = for
    nbNotifications <- ctx.me.so(notifyApi.unreadCount(_))
    withPerfs <- ctx.user.traverse(perfsRepo.withPerfs)
    given Option[UserWithPerfs] = withPerfs
    (data, povs) <- lobbyApi.get.mon(lila.mon.lobby.segment("lobbyApi"))
    playban <- ctx.userId.so(playbanApi.currentBan)
    blindGames <- ctx.blind.so(ctx.me).so(roundProxy.urgentGames)
    lichessMsg <- ctx.userId.ifTrue(nbNotifications > 0).so(unreadCount.hasMustReadLichessMsg)
    currentGame <- ctx.me.soUse(currentGameMyTurnOrJustMoved(povs))
  yield Homepage(
    data,
    playban,
    currentGame,
    blindGames,
    withPerfs,
    hasUnreadLichessMessage = lichessMsg
  )

  def currentGameMyTurnOrJustMoved(using me: Me): Fu[Option[CurrentGame]] =
    gameRepo
      .playingRealtimeNoAi(me)
      .flatMap:
        _.map(roundProxy.pov(_, me)).parallel.dmap(_.flatten)
      .flatMap(currentGameMyTurnOrJustMoved)

  private def currentGameMyTurnOrJustMoved(povs: List[Pov])(using me: Me): Fu[Option[CurrentGame]] =
    ~povs.collectFirst:
      case p1 if p1.game.nonAi && p1.game.hasClock && p1.isMyTurnOrJustMoved =>
        roundProxy.pov(p1.gameId, me).dmap(_ | p1).map { pov =>
          val opponent = lila.game.Namer.playerTextBlocking(pov.opponent)(using lightUserApi.sync)
          CurrentGame(pov = pov, opponent = opponent).some
        }

object Preload:

  case class Homepage(
      data: JsObject,
      playban: Option[TempBan],
      currentGame: Option[Preload.CurrentGame],
      blindGames: UrgentGames,
      me: Option[UserWithPerfs],
      hasUnreadLichessMessage: Boolean
  )

  case class CurrentGame(pov: Pov, opponent: String)
