package controllers

import play.api.libs.json.Json

import lila.app.{ *, given }
import lila.common.HTTPRequest
import lila.core.socket.Sri
import lila.game.AnonCookie
import lila.setup.Processor.HookResult

final class Setup(
    env: Env,
    challengeC: => Challenge
) extends LilaController(env)
    with lila.web.TheftPrevention:

  import env.setup.{ forms, processor }

  def ai = OpenBody:
    limit.setupBotAi(ctx.userId | UserId(""), () => rateLimited): () =>
      limit.setupPost(ctx.ip, rateLimited()):
        bindForm(forms.ai)(
          doubleJsonFormError,
          config =>
            processor.ai(config).flatMap { pov =>
              negotiateApi(
                html = redirectPov(pov),
                api = _ => env.api.roundApi.player(pov, scalalib.data.Preload.none).map(Created(_))
              )
            }
        )

  def friend(userId: Option[UserStr]) =
    OpenBody: ctx ?=>
      limit.setupPost(ctx.ip, rateLimited()):
        bindForm(forms.friend)(
          doubleJsonFormError,
          config =>
            for
              origUser <- ctx.user.traverse(env.user.perfsRepo.withPerf(_, config.perfType))
              destUser <- userId.so(env.user.api.enabledWithPerf(_, config.perfType))
              denied <- destUser.so(u => env.challenge.granter.isDenied(u.user, config.perfKey.some))
              result <- denied match
                case Some(denied) =>
                  val message = lila.challenge.ChallengeDenied.translated(denied)
                  negotiate(
                    // 403 tells setupCtrl.ts to close the setup modal
                    forbiddenJson(message), // TODO test
                    JsonBadRequest(message)
                  )
                case None =>
                  import lila.challenge.Challenge.*
                  (origUser, ctx.req.sid)
                    .match
                      case (Some(orig), _) => toRegistered(orig).some
                      case (_, Some(sid)) => Challenger.Anonymous(sid.value).some
                      case _ if HTTPRequest.isLichobile => Challenger.Open.some
                      case _ => none
                    .so: challenger =>
                      val timeControl = makeTimeControl(config.makeClock, config.makeDaysPerTurn)
                      val challenge = lila.challenge.Challenge.make(
                        size = config.size,
                        handicap = config.handicap,
                        timeControl = timeControl,
                        rated = config.rated,
                        color = config.color.name,
                        challenger = challenger,
                        destUser = destUser,
                        rematchOf = none
                      )
                      env.challenge.api
                        .create(challenge)
                        .flatMap:
                          if _ then
                            negotiate(
                              Redirect(routes.Round.watcher(challenge.gameId, Color.white)),
                              challengeC.showChallenge(challenge, justCreated = true)
                            )
                          else
                            negotiate(
                              Redirect(routes.Lobby.home),
                              JsonBadRequest("Challenge not created")
                            )
            yield result
        )

  private def hookResponse(res: HookResult) = res match
    case HookResult.Created(id) =>
      JsonOk:
        Json.obj(
          "ok" -> true,
          "hook" -> Json.obj("id" -> id)
        )
    case HookResult.Refused => JsonBadRequest(("Game was not created"))

  def hook(sri: Sri) = OpenOrScopedBody(parse.anyContent)(_.Web.Mobile, _.Web.Takex3): ctx ?=>
    NoBot:
      NoPlaybanOrCurrent:
        bindForm(forms.hook)(
          doubleJsonFormError,
          userConfig =>
            limit.setupPost(req.ipAddress, rateLimited):
              limit.setupAnonHook(req.ipAddress, () => rateLimited): () =>
                for
                  me <- ctx.user.traverse(env.user.api.withPerfs)
                  given Perf = me.fold(lila.rating.Perf.default)(_.perfs(userConfig.perfType))
                  blocking <- ctx.userId.so(env.relation.api.fetchBlocking)
                  res <- processor.hook(
                    userConfig.withinLimits,
                    sri,
                    req.sid,
                    lila.core.pool.Blocking(blocking)
                  )(using me)
                yield hookResponse(res)
        )

  def like(sri: Sri, gameId: GameId) = Open:
    NoBot:
      limit.setupPost(ctx.ip, rateLimited()):
        NoPlaybanOrCurrent:
          Found(env.game.gameRepo.game(gameId)): game =>
            for
              orig <- ctx.user.traverse(env.user.api.withPerfs)
              blocking <- ctx.userId.so(env.relation.api.fetchBlocking)
              hookConfig = lila.setup.HookConfig.default(ctx.isAuth)
              hookConfigWithRating = get("rr")
                .fold(
                  hookConfig.withRatingRange(
                    orig.fold(lila.rating.Perf.default)(_.perfs(game.perfKey)).intRating.some,
                    get("deltaMin"),
                    get("deltaMax")
                  )
                )(hookConfig.withRatingRange)
                .updateFrom(game)
              allBlocking = lila.core.pool.Blocking(blocking ++ game.userIds)
              hookResult <- processor.hook(hookConfigWithRating, sri, ctx.req.sid, allBlocking)(using orig)
            yield hookResponse(hookResult)

  def filterForm = Open:
    Ok.snip(views.setup.filter(forms.filter))

  private[controllers] def redirectPov(pov: Pov)(using ctx: Context) =
    val redir = Redirect(routes.Round.watcher(pov.gameId, Color.white))
    if ctx.isAuth then redir
    else
      redir.withCookies(
        env.security.lilaCookie.cookie(
          AnonCookie.name,
          pov.playerId.value,
          maxAge = AnonCookie.maxAge.some,
          httpOnly = false.some
        )
      )
