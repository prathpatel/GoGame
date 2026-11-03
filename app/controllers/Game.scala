package controllers

import play.api.mvc.*

import lila.app.{ *, given }
import lila.core.id.GameAnyId

final class Game(env: Env) extends LilaController(env):

  def bookmark(gameId: GameId) = AuthOrScopedBody(_.Web.Mobile, _.Preference.Write) { _ ?=> me ?=>
    env.bookmark.api
      .toggle(env.round.gameProxy.updateIfPresent)(gameId, me, getBoolOpt("v"))
      .inject(NoContent)
  }

  def delete(gameId: GameId) = Auth { _ ?=> me ?=>
    Found(env.game.gameRepo.game(gameId)): game =>
      if game.pgnImport.flatMap(_.user).exists(me.is(_)) then
        for
          _ <- env.bookmark.api.removeByGameId(game.id)
          _ <- env.game.gameRepo.remove(game.id)
          _ <- env.game.cached.clearNbImportedByCache(me)
        yield Redirect(routes.User.show(me.username))
      else Redirect(routes.Round.watcher(game.id, game.naturalOrientation))
  }

  /** Download a game as SGF. */
  def exportOne(id: GameAnyId) = OpenOrScoped(): ctx ?=>
    Found(env.round.proxyRepo.gameIfPresentOrFetch(id.gameId)): game =>
      env.game.sgfDump(game, withRating = ctx.pref.showRatings).map: sgf =>
        Ok(sgf)
          .asAttachment(env.game.sgfDump.filename(game))
          .withHeaders(headersForApiOrApp*)
          .as("application/x-go-sgf")

  private[controllers] def preloadUsers(game: lila.core.game.Game): Funit =
    env.user.lightUserApi.preloadMany(game.userIds)
  private[controllers] def preloadUsers(users: lila.core.user.GameUsers): Unit =
    env.user.lightUserApi.preloadUsers(users.all.collect:
      case Some(lila.core.user.WithPerf(u, _)) => u)
