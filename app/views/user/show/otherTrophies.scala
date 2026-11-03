package views.user
package show

import lila.app.UiEnv.{ *, given }
import lila.user.{ Trophy, TrophyKind }

object otherTrophies:

  import bits.awards.*

  def apply(info: lila.app.mashup.UserInfo)(using ctx: Context) =
    frag(
      info.trophies.trophies
        .filter(_.kind.klass.has("fire-trophy"))
        .nonEmptyOption
        .map: trophies =>
          div(cls := "stacked")(
            trophies.sorted.map: trophy =>
              trophy.kind.icon.map: iconChar =>
                maybeLink(trophy.anyUrl)(awardCls(trophy), ariaTitle(s"${trophy.kind.name}")):
                  raw(iconChar)
          ),
      info.trophies.trophies.find(_.kind._id == TrophyKind.zugMiracle).map(zugMiracleTrophy),
      info.trophies.trophies.filter(_.kind.withCustomImage).map { t =>
        maybeLink(t.anyUrl)(
          awardCls(t),
          ariaTitle(t.kind.name),
          style := "width: 65px; margin: 0 3px!important;"
        ):
          img(src := assetPath(s"images/trophy/${t.kind._id}.png"), cssWidth := 65, cssHeight := 80)
      },
      info.trophies.trophies.filter(_.kind.klass.has("icon3d")).sorted.map { trophy =>
        trophy.kind.icon.map: iconChar =>
          maybeLink(trophy.anyUrl)(awardCls(trophy), ariaTitle(trophy.kind.name)):
            raw(iconChar)
      },
    )
