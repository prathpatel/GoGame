package lila.api

import lila.common.Bus
import lila.core.perm.Granter
import lila.db.dsl.{ *, given }
import lila.user.UserDelete

/* There are 2 flavours of account termination.
|                           | disable                          | delete                |
|---------------------------|----------------------------------|-----------------------|
| how                       | from settings menu               | from /account/delete  |
| reopen                    | available to user                | strictly impossible   |
| games                     | intact                           | anonymized            |
| username                  | intact, no reuse                 | anonymized, no reuse  |
| email                     | kept for reopening               | deleted               |
| profile data              | hidden                           | deleted               |
| sessions and oauth tokens | closed                           | deleted               |
| follows and blocks        | deleted                          | deleted               |
 */
final class AccountTermination(
    userRepo: lila.user.UserRepo,
    playbanApi: lila.playban.PlaybanApi,
    relationApi: lila.relation.RelationApi,
    rankingApi: lila.user.RankingApi,
    challengeApi: lila.challenge.ChallengeApi,
    seekApi: lila.lobby.SeekApi,
    securityStore: lila.security.SessionStore,
    reportApi: lila.report.ReportApi,
    email: lila.mailer.AutomaticEmail,
    tokenApi: lila.oauth.AccessTokenApi,
    roundApi: lila.core.round.RoundApi,
    gameRepo: lila.game.GameRepo,
    chatApi: lila.chat.ChatApi
)(using Executor, Scheduler, org.apache.pekko.stream.Materializer):

  def disable(u: User, forever: Boolean)(using me: Me): Funit = for
    _ <- isEssential(u.id).so:
      fufail[Unit](s"Cannot disable essential account ${u.username}")
    playbanned <- playbanApi.hasCurrentPlayban(u.id)
    selfClose = me.is(u)
    modClose = !selfClose && Granter(_.CloseAccount)
    tos = u.marks.dirty || modClose || playbanned
    _ <- userRepo.disable(u, forever = forever)
    _ <- roundApi.resignAllGamesOf(u.id)
    _ <- relationApi.accountTermination(u)
    _ <- rankingApi.remove(u.id)
    _ <- challengeApi.removeByUserId(u.id)
    _ <- seekApi.removeByUser(u)
    _ <- securityStore.closeAllSessionsOf(u.id)
    _ <- selfClose.so(tokenApi.revokeAllByUser(u.id))
    _ <- reportApi.processAndGetBySuspect(lila.report.Suspect(u))
  yield Bus.pub(lila.core.security.CloseAccount(u.id))

  def scheduleDelete(u: User)(using Me): Funit = for
    _ <- disable(u, forever = false)
    _ <- email.delete(u)
    _ <- userRepo.delete.schedule(u.id, UserDelete(nowInstant).some)
  yield ()

  private[api] def garbageCollect(userId: UserId) =
    lichessDisable(userId)

  private[api] def lichessDisable(userId: UserId) =
    userRepo.lichessAnd(userId).flatMapz { (lichess, user) =>
      disable(user, forever = false)(using Me(lichess))
    }

  lila.common.LilaScheduler.variableDelay(
    "accountTermination.delete",
    delay = prev => _.Delay(if prev.isDefined then 1.second else 10.seconds),
    timeout = _.AtMost(1.minute),
    initialDelay = _.Delay(111.seconds)
  ):
    userRepo.delete.findNextScheduled.flatMapz: user =>
      if user.enabled.yes
      then userRepo.delete.schedule(user.id, none).inject(none)
      else doDeleteNow(user).inject(user.some)

  private def doDeleteNow(u: User): Funit = for
    _ <- isEssential(u.id).so:
      fufail[Unit](s"Cannot delete essential account ${u.username}")
    playbanned <- playbanApi.hasCurrentPlayban(u.id)
    tos = u.marks.dirty || playbanned
    _ = lila.log.system.info(s"Deleting user ${u.username} tos=$tos")
    _ <- if tos then userRepo.delete.nowWithTosViolation(u) else userRepo.delete.nowFully(u)
    _ <- gameRepo.deleteAllSinglePlayerOf(u.id)
    _ <- deleteAllGameChats(u)
    _ <- tokenApi.revokeAllByUser(u.id)
    _ <- u.marks.clean.so:
      securityStore.deleteAllSessionsOf(u.id)
  yield
    // a lot of deletion is done by modules listening to the following event:
    Bus.pub(lila.core.user.UserDelete(u))

  def deleteAllGameChats(u: User) =
    import lila.game.Query
    import lila.core.game.Source.*
    gameRepo
      .docCursor(Query.user(u.id) ++ Query.sourceIn(List(Lobby, Pool, Friend, Api)), bid(true).some)
      .documentSource()
      .mapConcat(_.getAsOpt[GameId]("_id").toList)
      .grouped(100)
      .mapAsync(1)(ids => chatApi.userChat.removeMessagesBy(ids, u.id))
      .run()

  private val isEssential: Set[UserId] =
    Set(
      UserId.lichess,
      UserId.broadcaster,
      UserId.irwin,
      UserId.kaladin,
      UserId.explorer,
      UserId.ai,
      UserId.lichess4545,
      UserId.watcherbot
    )
