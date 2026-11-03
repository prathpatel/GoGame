package goban

import chess.{ Clock, Color, ErrorStr, MoveMetrics, Ply }

/** The state of a game of Go, in the shape lila expects from a chess game:
  * a position, the move list, an optional clock and ply counters.
  *
  * Ply semantics follow lila: `ply.turn` is the color to move, where even plies
  * are White. Black plays first in an even game, so such games start at ply 1.
  * Handicap games, where White moves first, start at ply 0.
  */
case class Game(
    position: Position,
    moves: Vector[Move] = Vector.empty,
    clock: Option[Clock] = None,
    ply: Ply = Ply.firstMove,
    startedAtPly: Ply = Ply.firstMove,
    komi: Komi = Komi(7.5),
    handicap: Int = 0
):

  export position.{ color as player, board, size, prisoners, bothPassed }

  def playedPlies: Int = ply.value - startedAtPly.value

  def apply(move: Move, metrics: MoveMetrics = MoveMetrics.empty): Either[ErrorStr, Clock.WithCompensatedLag[Game]] =
    position
      .play(move)
      .map: next =>
        val newClock = clock.map: prev =>
          val c1 = metrics.frameLag.fold(prev)(prev.withFrameLag)
          val c2 = c1.step(metrics, gameActive = !next.bothPassed)
          // the clock starts after each player made one move
          if playedPlies == 1 then c2.map(_.start) else c2
        Clock.WithCompensatedLag(
          copy(
            position = next,
            moves = moves :+ move,
            ply = ply.next,
            clock = newClock.map(_.value)
          ),
          newClock.flatMap(_.compensated)
        )

  def play(move: Move): Either[ErrorStr, Game] = apply(move).map(_.value)

  def withClock(c: Option[Clock]) = copy(clock = c)

  /** Resume play after a pass/pass (e.g. players disagreed about dead stones). */
  def resume: Game = copy(position = position.copy(consecutivePasses = 0))

  def initialPosition: Position = Position.initial(size, handicap)

  /** Moves keys separated by spaces, for storage and the wire. */
  def moveKeys: String = moves.map(_.key(board.width)).mkString(" ")

object Game:

  def apply(size: BoardSize, komi: Komi, handicap: Int, clock: Option[Clock]): Game =
    val pos = Position.initial(size, handicap)
    val start = if pos.color.black then Ply.firstMove else Ply.initial
    Game(
      position = pos,
      clock = clock.map(_.copy(color = pos.color)),
      ply = start,
      startedAtPly = start,
      komi = komi,
      handicap = Handicap.points(size, handicap).size
    )

  def defaultKomi(handicap: Int): Komi = if handicap >= 2 then Komi(0.5) else Komi(7.5)

  /** Rebuild a game from its move list. Stops at the first illegal move. */
  def replay(
      size: BoardSize,
      komi: Komi,
      handicap: Int,
      moves: Iterable[Move],
      clock: Option[Clock] = None
  ): Either[ErrorStr, Game] =
    moves.foldLeft[Either[ErrorStr, Game]](Right(apply(size, komi, handicap, None))): (acc, m) =>
      acc.flatMap(_.play(m))
    .map(_.copy(clock = clock))

  def parseMoves(str: String, size: BoardSize): List[Move] =
    str.split(' ').toList.filter(_.nonEmpty).flatMap(Move.parse(_, size.size))

  /** Every intermediate position, starting with the initial one. Used for replays. */
  def positions(size: BoardSize, handicap: Int, moves: Iterable[Move]): List[Position] =
    moves
      .scanLeft[Option[Position]](Some(Position.initial(size, handicap))): (acc, m) =>
        acc.flatMap(_.play(m).toOption)
      .flatten
      .toList

  /** Lila stores colors as a chess ply turn; this is exposed for clarity. */
  def colorToMoveAt(ply: Ply): Color = ply.turn
