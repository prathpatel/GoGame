package controllers

import play.api.libs.json.*
import play.api.mvc.*
import scalalib.data.Preload

import lila.app.{ *, given }
import lila.chat.Chat
import lila.common.Json.given
import lila.core.id.{ GameAnyId, GameFullId }
import lila.round.RoundGame.*
import lila.round.UrgentGames
import lila.ui.Snippet

final class Round(
    env: Env,
    gameC: => Game,
    challengeC: => Challenge
) extends LilaController(env)
    with lila.web.TheftPrevention:

  private[controllers] def renderPlayer(pov: Pov)(using ctx: Context): Fu[Result] =
    for
      users <- env.user.api.gamePlayers(pov.game.userIdPair, pov.game.perfKey)
      _ = gameC.preloadUsers(users)
      res <- negotiateApi(
        html =
          if !pov.game.started then notFound
          else
            PreventTheft(pov):
              for
                (chatOption, crosstable, playing, bookmarked, data) <-
                  (
                    getPlayerChat(pov.game),
                    ctx.noBlind.so(env.game.crosstableApi.withMatchup(pov.game)),
                    pov.game.isSwitchable.so(otherPovs(pov.game)),
                    env.bookmark.api.exists(pov.game, ctx.me),
                    env.api.roundApi.player(pov, Preload(users))
                  ).tupled
                page <- renderPage(
                  views.round.player(
                    pov,
                    data,
                    cross = crosstable,
                    playing = playing,
                    chatOption = chatOption,
                    bookmarked = bookmarked
                  )
                )
              yield Ok(page).noCache
        ,
        api = _ =>
          if isTheft(pov) then theftResponse
          else
            for
              data <- env.api.roundApi.player(pov, Preload(users))
              chat <- getPlayerChat(pov.game)
            yield Ok(data.add("chat", chat.flatMap(_.game).map(_.lines))).noCache
      )
    yield res.enforceCrossSiteIsolation

  def player(fullId: GameFullId) = Open:
    Found(env.round.proxyRepo.pov(fullId))(renderPlayer)

  private def otherPovs(game: GameModel)(using ctx: Context) =
    ctx.me.so: user =>
      for urgent <- env.round.proxyRepo.urgentGames(user)
      yield urgent.map:
        _.filter: pov =>
          pov.gameId != game.id && pov.game.isSwitchable && pov.game.isSimul == game.isSimul

  def whatsNext(fullId: GameFullId) = Open:
    Found(env.round.proxyRepo.pov(fullId)): currentPov =>
      if currentPov.isMyTurn
      then Ok(Json.obj("nope" -> true))
      else
        otherPovs(currentPov.game)
          .map(_.selectNext(currentPov.game))
          .map: next =>
            Ok(Json.obj("next" -> next.map(_.fullId)))

  def next(gameId: GameId) = Auth { ctx ?=> me ?=>
    Found(env.round.proxyRepo.game(gameId)): currentGame =>
      otherPovs(currentGame)
        .map(_.selectNext(currentGame))
        .map(_.orElse(Pov(currentGame, me)))
        .flatMap:
          case Some(next) => renderPlayer(next)
          case None =>
            Redirect(routes.Round.watcher(gameId, Color.white))
  }

  def watcher(gameId: GameId, color: Color) = Open:
    env.round.proxyRepo
      .pov(gameId, color)
      .flatMap:
        case Some(pov) =>
          watch(if getUserStr("pov").map(_.id).exists(pov.opponent.userId.has) then !pov else pov)
        case None => challengeC.showId(gameId.into(lila.challenge.ChallengeId))

  def watcherRedirect(gameId: GameId, color: Color) = Anon:
    MovedPermanently(routes.Round.watcher(gameId, color))

  private def isBlockedByPlayer(game: GameModel)(using Context) =
    game.isBeingPlayed.so(env.relation.api.isBlockedByAny(game.userIds))

  private[controllers] def watch(pov: Pov, userTv: Option[UserModel] = None)(using
      ctx: Context
  ): Fu[Result] =
    limit.anyPageByUser.option(ctx.myId, rateLimited, cost = if isGrantedOpt(_.UserModView) then 0 else 1):
      val details = ctx.isAuth || pov.game.isStrongOrRecent
      playablePovForReq(pov.game) match
        case Some(player) if userTv.isEmpty => renderPlayer(pov.withColor(player.color))
        case _ =>
          isBlockedByPlayer(pov.game).flatMap:
            if _ then notFound
            else
              negotiateApi(
                html =
                    for
                      users <- env.user.api.gamePlayers(pov.game.userIdPair, pov.game.perfKey)
                      _ = gameC.preloadUsers(users)
                      chat <- getWatcherChat(pov.game)
                      crosstable <- (ctx.noBlind && details).so:
                        env.game.crosstableApi.withMatchup(pov.game)
                      bookmarked <- env.bookmark.api.exists(pov.game, ctx.me)
                      tv = userTv.map(u => lila.round.OnTv.User(u.id))
                      data <- env.api.roundApi.watcher(pov, users, tv)
                      page <- renderPage:
                        views.round.watcher(
                          pov,
                          data,
                          crosstable,
                          userTv = userTv,
                          chatOption = chat,
                          bookmarked = bookmarked
                        )
                    yield Ok(page)
                ,
                api = _ =>
                  for
                    users <- env.user.api.gamePlayers(pov.game.userIdPair, pov.game.perfKey)
                    data <- env.api.roundApi.watcher(pov, users, tv = none)
                    chat <- getWatcherChat(pov.game)
                  yield Ok:
                    data.add("chat" -> chat.map(_.lines))
              ).dmap(_.noCache)

  private[controllers] def getWatcherChat(
      game: GameModel
  )(using ctx: Context): Fu[Option[lila.chat.UserChat.Mine]] = {
    (ctx.noBot || ctx.userId.exists(game.userIds.has)) &&
    (ctx.isAuth || req.client.isHuman) && {
      game.finishedOrAborted || !ctx.userId.exists(game.userIds.has)
    }
  }.optionFu:
    env.chat.api.userChat.findMine(ChatId(s"${game.id}/w"), !game.justCreated)

  private[controllers] def getPlayerChat(game: GameModel)(using
      ctx: Context
  ): Fu[Option[Chat.GameOrEvent]] =
    game.hasChat.so:
      for
        chat <- env.chat.api.playerChat.findIf(game.id.into(ChatId), !game.justCreated)
        lines <- env.chat.json.asyncLines(chat)
      yield Chat
        .GameOrEvent:
          Left:
            Chat.Restricted(chat, lines, restricted = game.sourceIs(_.Lobby) && ctx.isAnon)
        .some

  def sides(gameId: GameId, color: Color) = Open:
    FoundSnip(env.round.proxyRepo.pov(gameId, color)): pov =>
      (
        env.game.crosstableApi.withMatchup(pov.game),
        env.bookmark.api.exists(pov.game, ctx.me)
      ).flatMapN: (crosstable, bookmarked) =>
        Snippet(views.game.sides(pov, crosstable, bookmarked = bookmarked))

  def writeNote(gameId: GameId) = AuthBody { ctx ?=> me ?=>
    bindForm(env.round.noteApi.form)(
      _ => BadRequest,
      text => env.round.noteApi.set(gameId, me, text.trim.take(10000)).inject(jsonOkResult)
    )
  }

  def readNote(gameId: GameId) = Auth { _ ?=> me ?=>
    env.round.noteApi.get(gameId, me).dmap(Ok(_))
  }

  def resign(fullId: GameFullId) = Open:
    Found(env.round.proxyRepo.pov(fullId)): pov =>
      val redirection = fuccess(Redirect(routes.Lobby.home))
      if isTheft(pov) then
        lila.round.logger.warn(s"theft resign $fullId ${ctx.ip}")
        redirection
      else
        env.round.resign(pov)
        org.apache.pekko.pattern.after(500.millis, env.system.scheduler)(redirection)

  def mini(gameId: GameId, color: Color) = Open:
    FoundSnip(
      env.round.proxyRepo
        .povIfPresent(gameId, color)
        .orElse(env.game.gameRepo.pov(gameId, color))
    )(pov => Snippet(views.game.mini(pov)))

  def miniFullId(fullId: GameFullId) = Open:
    FoundSnip(env.round.proxyRepo.povIfPresent(fullId).orElse(env.game.gameRepo.pov(fullId))): pov =>
      Snippet(views.game.mini(pov))

  def minis(ids: String) = Anon:
    val gameIds = ids.split(',').take(64).toList.flatMap(GameId.from)
    for games <- env.round.proxyRepo.gamesIfPresentOrFetch(gameIds)
    yield Ok.snip(views.game.mini.many(games))

  def apiAddTime(anyId: GameAnyId, seconds: Int) = Scoped(_.Challenge.Write) { _ ?=> me ?=>
    import lila.core.round.Moretime
    env.round.proxyRepo
      .pov(anyId.gameId, me)
      .flatMap:
        _.so: pov =>
          env.round.moretimer
            .isAllowedIn(pov.game, Preload.none, force = true)
            .map:
              if _ then
                env.round.roundApi.tell(pov.gameId, Moretime(pov.playerId, seconds.seconds, force = true))
                jsonOkResult
              else BadRequest(jsonError("This game doesn't allow giving time"))
  }

  def help = Open:
    Ok.snip(lila.web.ui.help.round(ctx.kid.no))
