package controllers

import org.apache.pekko.stream.scaladsl.*
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json.*
import play.api.mvc.*
import scalalib.paginator.Paginator

import lila.app.{ *, given }
import lila.common.HTTPRequest
import lila.common.Json.given
import lila.core.perf.UserWithPerfs
import lila.core.security.IsProxy
import lila.core.user.LightPerf
import lila.core.userId.UserSearch
import lila.game.GameFilter
import lila.mon.extensions.*
import lila.rating.PerfType
import lila.rating.UserPerfsExt.best8Perfs
import lila.security.{ UserAgentParser, UserLogins }
import lila.user.WithPerfsAndEmails

final class User(
    override val env: Env,
    roundC: => Round,
    gameC: => Game
) extends LilaController(env):

  import env.relation.api as relationApi
  import env.user.lightUserApi

  private given Conversion[UserWithPerfs, UserModel] = _.user

  def tv(username: UserStr) = Open:
    Found(meOrFetch(username)): user =>
      env.round.currentlyPlaying
        .exec(user.id)
        .orElse(env.round.lastPlayed(user.id))
        .flatMap:
          _.fold(fuccess(Redirect(routes.User.show(username)))): pov =>
            ctx.me.filterNot(_ => pov.game.bothPlayersHaveMoved).flatMap(Pov(pov.game, _)) match
              case Some(mine) => Redirect(routes.Round.player(mine.fullId))
              case _ => roundC.watch(pov, userTv = user.some)

  private def gamesForLichobile(u: UserModel, filter: String, page: Int)(using BodyContext[?]) =
    userGames(u, filter, page).flatMap(env.game.userGameApi.jsPaginator).map { res =>
      Ok(res ++ Json.obj("filter" -> GameFilter.all.name))
    }

  private val userShowHtmlRateLimit =
    env.security.ipTrust.rateLimit(3_000, 1.day, "user.show.html.ip", _.antiScraping(dch = 10, others = 4))

  def show(username: UserStr) = OpenBody:
    WithProxy: proxy ?=>
      EnabledUser(username, proxy.some): u =>
        negotiate(
          renderShow(u),
          gamesForLichobile(u, GameFilter.all.name, 1)
        )

  def search(term: String) = Open: _ ?=>
    UserStr.read(term) match
      case Some(username) => Redirect(routes.User.show(username)).toFuccess
      case _ if isGrantedOpt(_.UserSearch) => Redirect(s"${lila.ui.deadLink}?q=$term").toFuccess
      case _ => notFound

  private def isRestricted(using ctx: Context, proxy: IsProxy) =
    ctx.isAnon && (HTTPRequest.noReferer(ctx.req) || proxy.couldBeEnum)

  private def renderShow(u: UserModel, status: Results.Status = Results.Ok)(using
      ctx: BodyContext[?]
  )(using IsProxy): Fu[Result] =
    // there is no activity feed in this fork: the profile shows the player's games
    renderGames(u, GameFilter.all.name, 1, status)

  def gamesAll(username: UserStr, page: Int) = games(username, GameFilter.all.name, page)

  def games(username: UserStr, filter: String, page: Int) = OpenBody: ctx ?=>
    Reasonable(page):
      RequireAuthIf(UserAgentParser.trust.isSuspicious || page > 1):
        WithProxy: proxy ?=>
          EnabledUser(username): u =>
            renderGames(u, filter, page)

  private def renderGames(u: UserModel, filter: String, page: Int, status: Results.Status = Results.Ok)(
      using ctx: BodyContext[?]
  )(using IsProxy): Fu[Result] =
          limit.userProfileLimiter(rateLimited):
              val full = !isRestricted
              negotiate(
                html = for
                  nbs <- full.so(ctx.me).soUse(env.userNbGames(u, withCrosstable = true, withPlaying = true))
                  filters = lila.app.mashup.GameFilterMenu(u, nbs, filter, ctx.isAuth)
                  pag <- full.so(env.gamePaginator(u, nbs.some, filter = filters.current, page = page))
                  _ <- lightUserApi.preloadMany(pag.currentPageResults.flatMap(_.userIds))
                  res <-
                    if HTTPRequest.isSynchronousHttp(ctx.req) then
                      for
                        info <- env.userInfo.fetch(u, nbs, restricted = isRestricted)
                        social <- env.socialInfo(u)
                        page <- renderPage:
                          views.user.show.page.games(info, pag, filters, social)
                        res = status(page)
                      yield res
                    else Ok.snip(views.user.show.gamesContent(u, nbs, pag, filters, filter)).toFuccess
                yield res.withCanonical(routes.User.games(u.username, filters.current.name)),
                json = gamesForLichobile(u, filter, page)
              )

  private def EnabledUser(username: UserStr, proxy: Option[IsProxy] = none)(
      f: UserModel => Fu[Result]
  )(using ctx: Context): Fu[Result] =
    if username.id.isGhost
    then
      negotiate(
        Ok.page(views.user.show.page.deleted(false)),
        notFoundJson("Deleted user")
      )
    else
      def notFound(canCreate: Boolean) = negotiate(
        NotFound.page(views.user.show.page.deleted(canCreate)),
        NotFound(jsonError("No such player, or account closed"))
      )
      meOrFetch(username).flatMap:
        case None =>
          if username.id.is(UserId("me")) then
            negotiate(
              Redirect(routes.Auth.login.url, Map("referrer" -> List(ctx.req.uri))).toFuccess,
              authenticationFailed
            )
          else notFound(true)
        case Some(u) if u.enabled.yes || isGrantedOpt(_.AccountInfo) => f(u)
        case u => notFound(u.isEmpty)

  def showMini(username: UserStr) = Open:
    Found(env.user.api.withPerfs(username)): user =>
      ctx.userId
        .so(relationApi.fetchRelation(_, user.id))
        .flatMap: relation =>
          if user.enabled.yes || isGrantedOpt(_.AccountInfo)
          then
            (
              ctx.userId.so(relationApi.fetchBlocks(user.id, _)),
              ctx.userId.traverse(env.game.crosstableApi(user.id, _)),
              ctx.isAuth.so(env.pref.api.followable(user.id)),
              fuccess(none[String])
            ).flatMapN: (blocked, crosstable, followable, realName) =>
              negotiate(
                html = for
                  pov <- ctx.isnt(user).so(env.round.currentlyPlaying.exec(user.user.id))
                  ping = env.socket.isOnline.exec(user.id).so(env.socket.getLagRating(user.id))
                  snip <- Ok.snip:
                    views.user.mini(user, pov, blocked, followable, relation, ping, crosstable, none)
                yield snip.privateCache(5.seconds),
                json = // lichobile only
                  import lila.game.JsonView.given
                  Ok:
                    Json.obj(
                      "crosstable" -> crosstable,
                      "perfs" -> lila.user.JsonView.perfsJson(user.perfs, user.perfs.best8Perfs)
                    )
              )
          else Ok(views.user.bits.miniClosed(user.user, relation)).privateCache(10.minutes)

  def online = Anon:
    val max = 50
    negotiateJson:
      env.user.cached.getTop50Online.map: users =>
        Ok:
          Json.toJson:
            users
              .take(getInt("nb").fold(10)(_.min(max)))
              .map: u =>
                env.user.jsonView.full(u.user, u.perfs.some, withProfile = true)

  def ratingHistory(username: UserStr) = OpenOrScoped():
    EnabledUser(username): u =>
      env.history
        .ratingChartApi(u, computeIfNeeded = ctx.isAuth)
        .dmap: // send an empty JSON array if no history JSON is available
          _ | lila.core.data.SafeJsonStr("[]")
        .dmap(jsonStr => Ok(jsonStr).as(JSON))

  private def userGames(
      u: UserModel,
      filterName: String,
      page: Int
  )(using ctx: BodyContext[?]): Fu[Paginator[GameModel]] =
    limit.userGames(
      ctx.ip,
      fuccess(Paginator.empty[GameModel]),
      cost = page,
      msg = s"on ${u.username}"
    ):
      lila.mon.http.userGamesCost.increment(page.toLong)
      for
        pagFromDb <- env.gamePaginator(user = u, nbs = none, filter = GameFilter(filterName), page = page)
        pag <- pagFromDb.mapFutureResults(env.round.proxyRepo.upgradeIfPresent)
        _ <- lightUserApi.preloadMany(pag.currentPageResults.flatMap(_.userIds))
      yield pag

  def list = Open:
    env.user.cached.top10.get {}.flatMap { leaderboards =>
      negotiate(
        html = for
          nbAllTime <- env.user.cached.top10NbGame.get {}
          topOnline <- env.user.cached.getTop50Online
          page <- renderPage:
            views.user.list(topOnline, leaderboards, nbAllTime)
        yield Ok(page),
        json = // lichobile only
          given OWrites[LightPerf] = OWrites(env.user.jsonView.lightPerfIsOnline)
          import lila.user.JsonView.leaderboardsWrites
          JsonOk(leaderboards).publicCache(1.minute)
      )
    }

  def apiList = Anon:
    env.user.cached.top10.get {}.map { leaderboards =>
      import env.user.jsonView.lightPerfIsOnlineWrites
      import lila.user.JsonView.leaderboardsWrites
      JsonOk(leaderboards).publicCache(1.minute)
    }

  def top(perfKey: PerfKey, page: Int) = Open:
    Reasonable(page, Max(20)):
      env.user.cached
        .topPerfPager(perfKey, page)
        .flatMap: pager =>
          Ok.page(views.user.list.top(perfKey, pager))

  def topNbApi(nb: Int, perfKey: PerfKey) = Anon:
    if nb == 1 && perfKey == PerfKey.standard then
      env.user.cached.top10.get {}.map { leaderboards =>
        import env.user.jsonView.lightPerfIsOnlineWrites
        import lila.user.JsonView.leaderboardStandardTopOneWrites
        JsonOk(leaderboards).publicCache(1.minute)
      }
    else env.user.cached.firstPageOf(perfKey).dmap(_.take(nb)).map(topNbJson(_).publicCache(1.minute))

  private def topNbJson(users: Seq[LightPerf]) =
    given OWrites[LightPerf] = OWrites(env.user.jsonView.lightPerfIsOnline)
    Ok(Json.obj("users" -> users))

  def writeNote(username: UserStr) = AuthBody { ctx ?=> me ?=>
    bindForm(lila.user.UserForm.note)(
      err => BadRequest(err.errors.toString).toFuccess,
      data =>
        doWriteNote(username, data): user =>
            Ok.snipAsync:
              env.user.noteApi.getForMyPermissions(user).map {
                views.user.noteUi.zone(user, _)
              }
    )
  }

  def apiReadNote(username: UserStr) = Scoped(_.Note.Write) { _ ?=> me ?=>
    Found(meOrFetch(username)):
      env.user.noteApi
        .getForMyPermissions(_)
        .flatMap(lila.user.JsonView.notes)
        .map(Ok(_))
  }

  def apiWriteNote(username: UserStr) = ScopedBody(_.Note.Write) { ctx ?=> me ?=>
    bindForm(lila.user.UserForm.apiNote)(
      doubleJsonFormError,
      data => doWriteNote(username, data)(_ => jsonOkResult)
    )
  }

  private def doWriteNote(
      username: UserStr,
      data: lila.user.UserForm.NoteData
  )(f: UserModel => Fu[Result])(using Context, Me) =
    Found(meOrFetch(username)): user =>
      val isMod = data.mod && isGranted(_.ModNote)
      for
        _ <- env.user.noteApi.write(user.id, data.text, isMod, dox = isMod && data.dox)
        result <- f(user)
      yield result

  def deleteNote(id: String) = Auth { ctx ?=> me ?=>
    Found(env.user.noteApi.byId(id)): note =>
      (note.isFrom(me) && !note.mod).so:
        env.user.noteApi.delete(note._id).inject(Redirect(routes.User.show(note.to).url + "?note"))
  }

  def opponents = Auth { ctx ?=> me ?=>
    val user = meOrFetch(getUserStr("u")).map(_.filter(u => ctx.is(u) || isGrantedOpt(_.BoostHunter)))
    Found(user): user =>
      for
        usersAndGames <- env.game.favoriteOpponents(user.id)
        withPerfs <- env.user.api.listWithPerfs(usersAndGames._1F, includeClosed = false)
        ops = withPerfs.toList.zip(usersAndGames._2F)
        followables <- env.pref.api.followables(ops.map(_._1.id))
        relateds <-
          ops
            .zip(followables)
            .sequentially { case ((u, nb), followable) =>
              relationApi
                .fetchRelation(user.id, u.id)
                .map:
                  lila.relation.Related(u, nb.some, followable, _)
            }
        page <- renderPage(views.relation.opponents(user, relateds))
      yield Ok(page)
  }

  def autocomplete = OpenOrScoped(): ctx ?=>
    NoTor:
      if getBool("exists") then
        val name = get("term").orZero
        env.security.forms.signup.firstUsernameError(name) match
          case Some(error) => BadRequest(jsonError(error))
          case None => UserStr.read(name).so(env.user.repo.existsSec).map(JsonOk)
      else
        get("term")
          .flatMap(UserSearch.read)
          .fold(BadRequest("No search term provided").toFuccess): term =>
            for
              userIds <-
                  ctx.me.ifTrue(getBool("friend")) match
                    case Some(follower) =>
                      env.relation.api.searchFollowedBy(follower, term, 10).flatMap { userIds =>
                        val remaining = 10 - userIds.length
                        if remaining > 0 then
                          env.user.cached.userIdsLike(term).map { extraUserIds =>
                            userIds ++ (extraUserIds.diff(userIds)).take(remaining)
                          }
                        else fuccess(userIds)
                      }
                    case None =>
                      for
                        found <- env.user.cached.userIdsLike(term)
                        closed <- isGrantedOpt(_.AccountInfo).so(env.user.repo.userIdsLikeClosed(term))
                      yield found ::: closed
              result <-
                if getBool("names") then
                  for users <- lightUserApi.asyncMany(userIds)
                  yield Json.toJson(users.flatMap(_.map(_.name)))
                else if getBool("object") then
                  for users <- lightUserApi.asyncMany(userIds)
                  yield Json.obj:
                    "result" -> JsArray(users.collect { case Some(u) =>
                      lila.common.Json.lightUser
                        .write(u)
                        .add("online" -> env.socket.isOnline.exec(u.id))
                    })
                else fuccess(Json.toJson(userIds))
            yield Ok(result)

  def redirect(path: String) = Open:
    staticRedirect(path) |
      env.round
        .pathRedirect(path)
        .flatMapz:
          case lichobilePov: Pov => roundC.renderPlayer(lichobilePov).dmap(some)
          case call: Call => fuccess(MovedPermanently(call).some)
        .orElse(env.challenge.api.redirect(path).map2(Redirect(_)))
        .orElse(UserStr.read(path).so(tryRedirect))
        .getOrElse(notFound)

  def tryRedirect(username: UserStr)(using Context): Fu[Option[Result]] =
    meOrFetch(username).map:
      _.filter(_.enabled.yes || isGrantedOpt(_.SeeReport)).map: user =>
        Redirect(routes.User.show(user.username))
