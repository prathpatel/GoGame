package lila.user
package ui

import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

final class UserActionMenu(helpers: Helpers):
  import helpers.{ *, given }

  def apply(u: User, relationActions: List[MenuItem], canImpersonate: Boolean)(using ctx: Context) =
    Menu(
      List(
        Granter
          .opt(_.UserModView)
          .option(
            MenuItem(
              "Mod zone",
              Icon.Agent,
              lila.ui.deadLink.url,
              cssClass = Some("mod-zone-toggle")
            )
          ),
        ctx
          .is(u)
          .option(
            MenuItem(trans.site.editProfile.txt(), Icon.Gear, routes.Account.profile.url)
          ),
        (u.count.game > 0).option(
          MenuItem(trans.site.watch.txt(), Icon.AnalogTv, routes.User.tv(u.username).url)
        )
      ).flatten ++
        relationActions ++
        List(
          (ctx.isAuth && ctx.kid.no && ctx.isnt(u)).option(
            MenuItem(
              trans.site.reportXToModerators.txt(u.username),
              Icon.CautionTriangle,
              s"${lila.ui.deadLink}?username=${u.username}"
            )
          ),
          (ctx.is(u) || Granter.opt(_.CloseAccount)).option(
            MenuItem(trans.site.friends().render, Icon.User, routes.Relation.following(u.username).url)
          ),
          (ctx.is(u) || Granter.opt(_.BoostHunter)).option(
            MenuItem(
              trans.site.favoriteOpponents().render,
              Icon.User,
              s"${routes.User.opponents}?u=${u.username}"
            )
          ),
          ctx
            .is(u)
            .option(
              MenuItem(trans.site.listBlockedPlayers.txt(), Icon.NotAllowed, routes.Relation.blocks().url)
            ),
          ctx.me
            .filter(_.is(u))
            .ifTrue(Granter.opt(_.LichessTeam))
            .map: me =>
              MenuItem("My permissions", Icon.Gear, lila.ui.deadLink.url),
          canImpersonate.option(
            MenuItem(
              "Impersonate",
              Icon.Agent,
              lila.ui.deadLink.url,
              httpMethod = Some(HttpMethod.POST)
            )
          )
        ).flatten,
      trans.site.more.txt()
    )
