package lila.pool

import chess.Clock
import play.api.libs.json.Json

import lila.core.pool.IsClockCompatible

object PoolList:

  import PoolConfig.{ *, given }

  extension (i: Int)
    def ++(increment: Int) = Clock.Config(Clock.LimitSeconds(i * 60), Clock.IncrementSeconds(increment))
    def players = NbPlayers(i)

  private def pool(size: goban.BoardSize, clock: Clock.Config, every: Int, nb: Int) =
    PoolConfig(size, clock, Wave(every.seconds, nb.players))

  import goban.BoardSize.*

  /** Quick pairing pools: three per board size, with Fischer clocks sized for Go.
    * A player makes about 40 moves on 9×9, 80 on 13×13 and 120 to 150 on 19×19, so the
    * bigger boards get more time per move.
    */
  val all: List[PoolConfig] = List(
    pool(Nine, 1 ++ 5, 15, 30),
    pool(Nine, 3 ++ 5, 15, 30),
    pool(Nine, 5 ++ 10, 20, 26),
    pool(Thirteen, 5 ++ 10, 20, 26),
    pool(Thirteen, 10 ++ 10, 25, 24),
    pool(Thirteen, 15 ++ 15, 30, 20),
    pool(Nineteen, 10 ++ 15, 30, 20),
    pool(Nineteen, 20 ++ 20, 40, 20),
    pool(Nineteen, 30 ++ 30, 60, 16)
  )

  private val keys: Set[(Int, String)] = all.view.map(p => p.size.size -> p.clock.show).toSet

  given isClockCompatible: IsClockCompatible = IsClockCompatible: (size, clock) =>
    keys.contains(size -> clock.show)

  def json(using lila.core.i18n.Translator) = Json.toJson(all)
