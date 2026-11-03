package lila.user
package ui

import lila.core.perf.UserWithPerfs
import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

final class UserShowSide(helpers: Helpers):
  import helpers.{ *, given }

  def apply(
      u: UserWithPerfs,
      rankMap: lila.core.rating.UserRankMap,
      active: Option[PerfKey]
  )(using ctx: Context) =

    def showNonEmptyPerf(perf: Perf, pk: PerfKey) =
      perf.nonEmpty.option(showPerf(perf, pk))

    def showPerf(perf: Perf, pk: PerfKey) =
      a(
        dataIcon := pk.perfIcon,
        title := pk.perfDesc.txt(),
        cls := List(
          "empty" -> perf.isEmpty,
          "active" -> active.contains(pk)
        ),
        href := ctx.pref.showRatings.option(s"/player/top/${pk.value}"),
        span(
          h3(pk.perfTrans),
          st.rating(
            ctx.pref.showRatings.option(
              frag(
                if perf.glicko.clueless then strong("?")
                else
                  strong(
                    perf.glicko.intRating,
                    perf.provisional.yes.option("?")
                  )
                ,
                " ",
                perf.glicko.clueless.not.so(ratingProgress(perf.progress)),
                " "
              )
            ),
            span(trans.site.nbGames.plural(perf.nb, perf.nb.localize))
          ),
          rankMap.get(pk).ifTrue(ctx.pref.showRatings).map { rank =>
            span(cls := "rank", title := trans.site.rankIsUpdatedEveryNbMinutes.pluralSameTxt(15))(
              trans.site.rankX(rank.localize)
            )
          }
        ),
        ctx.pref.showRatings.option(iconTag(Icon.PlayTriangle))
      )

    // Go ratings, one per speed
    div(cls := "side sub-ratings")(
      (!u.lame || ctx.is(u) || Granter.opt(_.AccountInfo)).option(
        frag(
          showNonEmptyPerf(u.perfs.ultraBullet, PerfKey.ultraBullet),
          showNonEmptyPerf(u.perfs.bullet, PerfKey.bullet),
          showPerf(u.perfs.blitz, PerfKey.blitz),
          showPerf(u.perfs.rapid, PerfKey.rapid),
          showPerf(u.perfs.classical, PerfKey.classical),
          showPerf(u.perfs.correspondence, PerfKey.correspondence)
        )
      )
    )
