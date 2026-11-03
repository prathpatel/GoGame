package views.user

import lila.app.UiEnv.{ *, given }
import lila.core.perf.UserWithPerfs
import lila.rating.UserPerfsExt.bestAny3Perfs
import lila.user.LightCount

object list:

  private lazy val ui = lila.user.ui.UserList(helpers, bits)
  export ui.top

  def apply(
      online: List[UserWithPerfs],
      leaderboards: lila.rating.UserPerfs.Leaderboards,
      nbAllTime: List[LightCount]
  )(using Context) =
    ui.page(online, leaderboards, nbAllTime, ol())

  def bots(users: List[UserWithPerfs])(using Context) = ui.bots(users, _.bestAny3Perfs)
