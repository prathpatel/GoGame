package controllers

import play.api.libs.json.Json
import play.api.mvc.{ RequestHeader, Result }
import scalalib.net.Bearer

import lila.app.{ *, given }
import lila.challenge.{ Challenge as ChallengeModel, Direction }
import lila.core.id.ChallengeId
import lila.game.AnonCookie
import lila.memo.RateLimit.Limited
import lila.oauth.{ EndpointScopes, OAuthScope, OAuthServer }

final class Challenge(env: Env) extends LilaController(env):
  def api = env.challenge.api

  def all = Auth { ctx ?=> me ?=>
    XhrOrRedirectHome:
      api.allFor(me).map(env.challenge.jsonView.apply).map(Ok(_))
  }

  def apiList = ScopedBody(_.Challenge.Read, _.Web.Mobile) { ctx ?=> me ?=>
    for all <- api.allFor(me, 300)
    yield Ok(env.challenge.jsonView.all(all))
  }

  def show(id: ChallengeId, @annotation.nowarn color: Option[Color]) = Open:
    showId(id)

  def apiShow(id: ChallengeId) = AnonOrScoped(_.Challenge.Read, _.Web.Mobile) { ctx ?=>
    Found(api.byId(id)): c =>
      val direction: Option[Direction] =
        if isMine(c) then Direction.Out.some
        else if isForMe(c) then Direction.In.some
        else none
      direction.so: dir =>
        for
          fullId <- c.accepted.so(env.round.proxyRepo.game(c.gameId).map2(c.fullIdOf(_, dir)))
          socketVersion <- ctx.isMobileOauth.so(env.challenge.version(c.id).dmap(some))
          json = env.challenge.jsonView.apiAndMobile(c, socketVersion, dir.some, fullId)
        yield Ok(json)
  }

  protected[controllers] def showId(id: ChallengeId)(using Context): Fu[Result] =
    Found(api.byId(id))(showChallenge(_))

  protected[controllers] def showChallenge(
      c: ChallengeModel,
      error: Option[String] = None,
      justCreated: Boolean = false
  )(using ctx: Context): Fu[Result] = for
    version <- env.challenge.version(c.id)
    mine = justCreated || isMine(c)
    direction =
      if mine then Direction.Out.some
      else if isForMe(c) then Direction.In.some
      else none
    json = env.challenge.jsonView.websiteAndLichobile(c, version, direction)
    res <- negotiate(
      html =
        if mine then
          targetSuggestions.flatMap: targets =>
            val page = views.challenge.mine(c, json, targets, error, getColor())
            if error.isDefined then BadRequest.page(page) else Ok.page(page)
        else
          Ok.async:
            for
              challenger <- c.challengerUserId.so(env.user.api.byIdWithPerf(_, c.perfType))
              relation <- (ctx.userId, c.challengerUserId).tupled.so(env.relation.api.fetchRelation.tupled)
            yield views.challenge.theirs(c, json, challenger, getColor(), relation)
      ,
      json = Ok(json)
    ).flatMap(withChallengeAnonCookie(mine && c.challengerIsAnon, c, owner = true))
  yield env.security.lilaCookie.ensure(res)

  private def targetSuggestions(using me: Option[Me]) = me.so: me =>
    for
      challengers <- env.game.gameRepo.recentChallengersOf(me.userId, Max(10))
      blocked <- env.relation.api.filterBlocked(me.userId, challengers)
      filtered = challengers.filterNot(blocked)
      targets <- env.user.lightUserApi.asyncManyFallback(filtered)
    yield targets

  private def isMine(challenge: ChallengeModel)(using Context) =
    challenge.challenger match
      case lila.challenge.Challenge.Challenger.Anonymous(secret) =>
        anonSecretFromCookieOrMobileSri.exists(_ == secret)
      case lila.challenge.Challenge.Challenger.Registered(userId, _) => ctx.is(userId)
      case lila.challenge.Challenge.Challenger.Open => false

  private def isForMe(challenge: ChallengeModel)(using me: Option[Me]) =
    challenge.destUserId.forall(dest => me.exists(_.is(dest))) &&
      !challenge.challengerUserId.so(orig => me.exists(_.is(orig)))

  import cats.mtl.Handle.*
  def accept(id: ChallengeId, color: Option[Color]) = Open:
    Found(api.byId(id)): c =>
      isForMe(c).so:
        allow:
          api
            .accept(c, ctx.req.sid.map(_.value), color)
            .flatMap:
              _.fold("The Challenge has already been accepted".raise): pov =>
                negotiateApi(
                  html = Redirect(routes.Round.watcher(pov.gameId, color | Color.white)),
                  api = _ => env.api.roundApi.player(pov, scalalib.data.Preload.none).map(Ok(_))
                )
            .flatMap(withChallengeAnonCookie(ctx.isAnon, c, owner = false))
        .rescue: err =>
          negotiate(
            Redirect(routes.Round.watcher(c.gameId, color | Color.white)),
            notFoundJson(err)
          )

  private def withChallengeAnonCookie(cond: Boolean, c: ChallengeModel, owner: Boolean)(
      res: Result
  ): Fu[Result] =
    cond
      .so:
        env.game.gameRepo.game(c.id.into(GameId)).map2 { game =>
          env.security.lilaCookie.cookie(
            AnonCookie.name,
            game.player(if owner then c.finalColor else !c.finalColor).id.value,
            maxAge = AnonCookie.maxAge.some,
            httpOnly = false.some
          )
        }
      .map:
        _.foldLeft(res)(_.withCookies(_))

  def decline(id: ChallengeId) = AuthBody { ctx ?=> _ ?=>
    Found(api.byId(id)): c =>
      isForMe(c).so:
        api
          .decline(
            c,
            bindForm(env.challenge.forms.decline)(_ => ChallengeModel.DeclineReason.default, _.realReason)
          )
          .inject(NoContent)
  }
  def cancel(id: ChallengeId) =
    Open:
      Found(api.byId(id)): c =>
        if isMine(c)
        then api.cancel(c).inject(NoContent)
        else notFound

  def toFriend(id: ChallengeId) = AuthBody { ctx ?=> _ ?=>
    Found(api.byId(id)): c =>
      if isMine(c) then
        bindForm(env.setup.forms.toFriend)(
          _ => NoContent,
          username =>
            limit.challenge(ctx.ip, rateLimited):
              def redir = Redirect(routes.Challenge.show(c.id))
              env.user.repo.byId(username).flatMap {
                case None => redir
                case Some(dest) if ctx.is(dest) => redir
                case Some(dest) =>
                  env.challenge.granter.isDenied(dest, c.perfType.key.some).flatMap {
                    case Some(denied) =>
                      showChallenge(c, lila.challenge.ChallengeDenied.translated(denied).some)
                    case None => api.setDestUser(c, dest).inject(redir)
                  }
              }
        )
      else notFound
  }

  def offerRematchForGame(gameId: GameId) = AuthOrScoped(_.Web.Mobile) { _ ?=> me ?=>
    NoBot:
      Found(env.game.gameRepo.game(gameId)): g =>
        g.opponentOf(me).flatMap(_.userId).so(env.user.repo.byId).orNotFound { opponent =>
          env.challenge.granter
            .isDenied(opponent, g.perfKey.some)
            .flatMap:
              case Some(d) => BadRequest(jsonError(lila.challenge.ChallengeDenied.translated(d)))
              case _ =>
                api
                  .offerRematchForGame(g, me)
                  .map:
                    if _ then jsonOkResult
                    else BadRequest(jsonError("Sorry, couldn't create the rematch."))
        }
  }

  private def anonSecretFromCookieOrMobileSri(using RequestHeader) =
    req.sid.map(_.value) orElse lila.security.Mobile.LichessMobileUa.sriFromUA.map(_.value)
