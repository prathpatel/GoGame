package goban

import chess.{ ByColor, Color, ErrorStr }

/** A Go position: the board, whose turn it is, prisoners, and the set of
  * previous board hashes used to enforce positional superko.
  */
case class Position(
    board: Board,
    color: Color,
    prisoners: ByColor[Int] = ByColor(0, 0),
    consecutivePasses: Int = 0,
    seen: Set[Long] = Set.empty,
    lastMove: Option[Move] = None,
    lastCaptures: Set[Point] = Set.empty
):

  export board.size

  /** Both players passed in a row: play stops and the scoring phase begins. */
  def bothPassed: Boolean = consecutivePasses >= 2

  def play(move: Move): Either[ErrorStr, Position] = move match
    case Move.Pass =>
      Right(
        copy(
          color = !color,
          consecutivePasses = consecutivePasses + 1,
          lastMove = Some(Move.Pass),
          lastCaptures = Set.empty
        )
      )
    case Move.Play(p) =>
      if p.index < 0 || p.index >= board.points then Left(ErrorStr(s"Point ${p.index} is off the board"))
      else if !board.isEmpty(p) then Left(ErrorStr(s"${p.human(board.width)} is occupied"))
      else
        val (next, captured) = board.place(p, color)
        if captured.isEmpty && next.group(p).liberties.isEmpty then
          Left(ErrorStr(s"${p.human(board.width)} is suicide"))
        else if next.hash == board.hash || seen.contains(next.hash) then
          Left(ErrorStr(s"${p.human(board.width)} repeats a previous position (ko)"))
        else
          Right(
            Position(
              board = next,
              color = !color,
              prisoners = prisoners.update(color, _ + captured.size),
              consecutivePasses = 0,
              seen = seen + board.hash,
              lastMove = Some(move),
              lastCaptures = captured
            )
          )

  def isLegal(p: Point): Boolean = play(Move.Play(p)).isRight

  def legalPoints: List[Point] = board.allPoints.filter(p => board.isEmpty(p) && isLegal(p)).toList

  /** The point currently forbidden by a simple ko, for display purposes. */
  def koPoint: Option[Point] =
    lastMove match
      case Some(Move.Play(_)) if lastCaptures.size == 1 =>
        lastCaptures.headOption.filter(p => !isLegal(p))
      case _ => None

object Position:

  def initial(size: BoardSize, handicap: Int = 0): Position =
    val stones = Handicap.points(size, handicap)
    val board = stones.foldLeft(Board.empty(size))((b, p) => b.setStone(p, Stone.black))
    Position(board = board, color = if stones.nonEmpty then Color.White else Color.Black)

object Handicap:

  def max(size: BoardSize): Int = if size.size == 9 then 5 else 9

  /** Traditional fixed handicap placement. */
  def points(size: BoardSize, handicap: Int): List[Point] =
    val n = size.size
    val edge = if n < 13 then 2 else 3
    val far = n - 1 - edge
    val mid = n / 2
    def p(x: Int, y: Int) = Point(x, y, n)
    val tr = p(far, edge)
    val bl = p(edge, far)
    val br = p(far, far)
    val tl = p(edge, edge)
    val center = p(mid, mid)
    val left = p(edge, mid)
    val right = p(far, mid)
    val top = p(mid, edge)
    val bottom = p(mid, far)
    val h = math.min(handicap, max(size))
    h match
      case h if h < 2 => Nil
      case 2 => List(tr, bl)
      case 3 => List(tr, bl, br)
      case 4 => List(tr, bl, br, tl)
      case 5 => List(tr, bl, br, tl, center)
      case 6 => List(tr, bl, br, tl, left, right)
      case 7 => List(tr, bl, br, tl, left, right, center)
      case 8 => List(tr, bl, br, tl, left, right, top, bottom)
      case _ => List(tr, bl, br, tl, left, right, top, bottom, center)
