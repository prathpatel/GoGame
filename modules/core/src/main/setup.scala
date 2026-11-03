package lila.core
package setup

import _root_.chess.{ Clock, Rated }
import _root_.goban.BoardSize
import scalalib.model.Days

import lila.core.game.GameRule
import lila.core.userId.UserId

trait OpenConfig:
  val name: Option[String]
  val boardSize: BoardSize
  val clock: Option[Clock.Config]
  val days: Option[Days]
  val rated: Rated
  val userIds: Option[PairOf[UserId]]
  val rules: Set[game.GameRule]
  val expiresAt: Option[Instant]

trait SetupForm:
  import play.api.data.Mapping
  private type Named[T] = (String, Mapping[T])
  def boardSize: Named[Int]
  def message: Named[Option[String]]
  def clock: Named[Option[Clock.Config]]
  def optionalDays: Named[Option[Days]]
  def rules: Named[Option[Set[GameRule]]]
