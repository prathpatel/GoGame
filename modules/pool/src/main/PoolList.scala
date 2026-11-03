package lila.pool

import chess.Clock
import play.api.libs.json.Json

import lila.core.pool.IsClockCompatible

object PoolList:

  import PoolConfig.{ *, given }

  extension (i: Int)
    def ++(increment: Int) = Clock.Config(Clock.LimitSeconds(i * 60), Clock.IncrementSeconds(increment))
    def players = NbPlayers(i)

  // quick pairing pools, all on the 19×19 board
  val all: List[PoolConfig] = List(
    PoolConfig(3 ++ 3, Wave(15.seconds, 30.players)),
    PoolConfig(5 ++ 5, Wave(18.seconds, 30.players)),
    PoolConfig(10 ++ 5, Wave(20.seconds, 26.players)),
    PoolConfig(10 ++ 10, Wave(22.seconds, 26.players)),
    PoolConfig(15 ++ 10, Wave(30.seconds, 20.players)),
    PoolConfig(20 ++ 20, Wave(35.seconds, 20.players)),
    PoolConfig(30 ++ 20, Wave(40.seconds, 20.players)),
    PoolConfig(45 ++ 30, Wave(60.seconds, 16.players)),
    PoolConfig(60 ++ 30, Wave(60.seconds, 16.players))
  )

  val clockStringSet: Set[String] = all.view.map(_.clock.show) to Set

  given isClockCompatible: IsClockCompatible = IsClockCompatible: clock =>
    clockStringSet.contains(clock.show)

  def json(using lila.core.i18n.Translator) = Json.toJson(all)
