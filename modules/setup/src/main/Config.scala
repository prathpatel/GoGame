package lila.setup

import chess.{ Clock, Speed }
import scalalib.model.Days

import lila.lobby.TriColor
import lila.rating.PerfType

private[setup] trait Config:

  // Whether or not to use a clock
  val timeMode: TimeMode

  // Clock time in minutes
  val time: Double

  // Clock increment in seconds
  val increment: Clock.IncrementSeconds

  // Correspondence days per turn
  val days: Days

  // Board size (9, 13, 19)
  val size: goban.BoardSize

  // Number of handicap stones given to Black (0 for an even game)
  val handicap: Int

  def hasClock = timeMode == TimeMode.RealTime

  def komi: goban.Komi = goban.Game.defaultKomi(handicap)

  def makeGame: goban.Game = goban.Game(size, komi, handicap, makeClock.map(_.toClock))

  def validHandicap = handicap == 0 || (handicap >= 2 && handicap <= goban.Handicap.max(size))

  def validClock = !hasClock || clockHasTime

  def validSpeed(isBot: Boolean) =
    !isBot || makeClock.forall: c =>
      Speed(c) >= Speed.Bullet

  def clockHasTime = time + increment.value > 0

  def makeClock = hasClock.option(justMakeClock)

  protected def justMakeClock =
    Clock.Config(
      Clock.LimitSeconds((time * 60).toInt),
      if clockHasTime then increment else Clock.IncrementSeconds(1)
    )

  def makeDaysPerTurn: Option[Days] = (timeMode == TimeMode.Correspondence).option(days)

  def makeSpeed: Speed = chess.Speed(makeClock)

  def perfType: PerfType = lila.rating.PerfType(chess.variant.Standard, makeSpeed)
  def perfKey = perfType.key

trait WithColor:
  self: Config =>

  // creator player color
  def color: TriColor

  lazy val creatorColor: Color = color.resolve()

object Config extends BaseConfig

trait BaseConfig:
  val sizes = goban.BoardSize.all.map(_.size)
  val sizeDefault = goban.BoardSize.default
  val handicaps = 0 :: (2 to 9).toList

  val speeds = Speed.all.map(_.id)

  private val timeMin = 0
  private val timeMax = 180
  private val acceptableFractions = Set(1 / 4d, 1 / 2d, 3 / 4d, 3 / 2d)
  def validateTime(t: Double) =
    t >= timeMin && t <= timeMax && (t.isWhole || acceptableFractions(t))

  private val incrementMin = Clock.IncrementSeconds(0)
  private val incrementMax = Clock.IncrementSeconds(180)
  def validateIncrement(i: Clock.IncrementSeconds) = i >= incrementMin && i <= incrementMax
