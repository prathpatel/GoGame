package lila.api

import play.api.libs.json.*
import scalalib.data.Preload

import lila.api.Context.given
import lila.common.Json.given
import lila.core.perm.Granter
import lila.core.user.GameUsers
import lila.mon.extensions.*
import lila.round.{ ExportOptions, JsonView }

final private[api] class RoundApi(
    jsonView: JsonView,
    noteApi: lila.round.NoteApi,
    bookmarkApi: lila.bookmark.BookmarkApi,
    userApi: lila.user.UserApi,
    prefApi: lila.pref.PrefApi,
    userLag: lila.socket.UserLagCache
)(using Executor):

  def player(pov: Pov, users: Preload[GameUsers])(using ctx: Context): Fu[JsObject] = {
    for
      users <- users.orLoad(userApi.gamePlayers(pov.game.userIdPair, pov.game.perfKey))
      prefs <- prefApi.get(users.map(_.map(_.user)), pov.color, ctx.pref)
      json <- jsonView.playerJson(pov, prefs, users, ctxFlags)
      bookmarked <- bookmarkApi.exists(pov.game, ctx.me)
    yield withBookmark(bookmarked).compose(withOpponentSignal(pov))(json)
  }.mon(lila.mon.round.api.player)

  def watcher(
      pov: Pov,
      users: GameUsers,
      tv: Option[lila.round.OnTv]
  )(using ctx: Context): Fu[JsObject] = {
    for
      json <- jsonView.watcherJson(pov, users, ctx.pref.some, ctx.me, tv, ctxFlags)
      bookmarked <- bookmarkApi.exists(pov.game, ctx.me)
    yield withBookmark(bookmarked)(json)
  }.mon(lila.mon.round.api.watcher)

  /** A finished game, for replay. */
  def review(pov: Pov, users: GameUsers)(using ctx: Context): Fu[JsObject] =
    watcher(pov, users, none)

  private def ctxFlags(using ctx: Context) =
    ExportOptions(
      blurs = Granter.opt(_.ViewBlurs),
      rating = ctx.pref.showRatings,
      nvui = ctx.blind
    )

  private def withBookmark(v: Boolean)(json: JsObject) =
    json.add("bookmarked" -> v)

  private def withOpponentSignal(pov: Pov)(json: JsObject) =
    if pov.game.speed <= chess.Speed.Blitz then
      json.add("opponentSignal", pov.opponent.userId.flatMap(userLag.getLagRating))
    else json
