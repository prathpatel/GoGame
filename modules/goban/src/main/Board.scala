package goban

import chess.Color

/** Immutable Go board. Cells are 0 (empty), 1 (black), 2 (white). */
final class Board private (val size: BoardSize, private val cells: Array[Byte]):

  inline def width: Int = size.size
  def points: Int = cells.length

  def apply(p: Point): Byte = cells(p.index)
  def colorAt(p: Point): Option[Color] = Stone.color(cells(p.index))
  def isEmpty(p: Point): Boolean = cells(p.index) == Stone.empty
  def isEmptyBoard: Boolean = cells.forall(_ == Stone.empty)

  def stoneCount(c: Color): Int =
    val s = Stone.of(c)
    cells.count(_ == s)

  def neighbours(p: Point): List[Point] = Board.neighbourTable(size)(p.index)

  /** The connected group containing `p` and its liberties. Empty if `p` is empty. */
  def group(p: Point): Board.Group =
    val stone = cells(p.index)
    if stone == Stone.empty then Board.Group(Set.empty, Set.empty)
    else
      val stones = scala.collection.mutable.Set(p)
      val libs = scala.collection.mutable.Set.empty[Point]
      var frontier = List(p)
      while frontier.nonEmpty do
        val cur = frontier.head
        frontier = frontier.tail
        neighbours(cur).foreach: n =>
          val c = cells(n.index)
          if c == Stone.empty then libs += n
          else if c == stone && !stones.contains(n) then
            stones += n
            frontier = n :: frontier
      Board.Group(stones.toSet, libs.toSet)

  /** Place a stone without any legality checks, then resolve captures.
    * Returns the new board and the captured points.
    */
  def place(p: Point, color: Color): (Board, Set[Point]) =
    val next = cells.clone()
    next(p.index) = Stone.of(color)
    val placed = Board(size, next)
    val enemy = Stone.of(!color)
    val captured = neighbours(p)
      .filter(n => next(n.index) == enemy)
      .map(placed.group)
      .filter(_.liberties.isEmpty)
      .flatMap(_.stones)
      .toSet
    captured.foreach(c => next(c.index) = Stone.empty)
    (placed, captured)

  def remove(points: Iterable[Point]): Board =
    val next = cells.clone()
    points.foreach(p => next(p.index) = Stone.empty)
    Board(size, next)

  def setStone(p: Point, stone: Byte): Board =
    val next = cells.clone()
    next(p.index) = stone
    Board(size, next)

  lazy val hash: Long =
    var h = 0L
    var i = 0
    while i < cells.length do
      if cells(i) != Stone.empty then h ^= Zobrist.table(i * 2 + cells(i) - 1)
      i += 1
    h

  /** One char per point, row by row: '.', 'b', 'w'. */
  def serialize: String = String(cells.map(Stone.char))

  def toArray: Array[Byte] = cells.clone()

  def allPoints: Iterator[Point] = (0 until cells.length).iterator.map(Point.fromIndex)

  override def equals(other: Any) = other match
    case b: Board => b.size == size && java.util.Arrays.equals(b.cells, cells)
    case _ => false
  override def hashCode = hash.toInt

  override def toString =
    serialize.grouped(width).mkString("\n")

object Board:

  case class Group(stones: Set[Point], liberties: Set[Point])

  def empty(size: BoardSize): Board = Board(size, Array.fill(size.points)(Stone.empty))

  def apply(size: BoardSize, cells: Array[Byte]): Board =
    require(cells.length == size.points, s"Board of size ${size.size} needs ${size.points} cells")
    new Board(size, cells)

  def parse(size: BoardSize, str: String): Option[Board] =
    val chars = str.filterNot(_.isWhitespace)
    Option.when(chars.length == size.points):
      Board(
        size,
        chars
          .map:
            case 'b' | 'x' | 'X' | 'B' => Stone.black
            case 'w' | 'o' | 'O' | 'W' => Stone.white
            case _ => Stone.empty
          .toArray
      )

  private[goban] val neighbourTable: Map[BoardSize, Array[List[Point]]] =
    BoardSize.all
      .map: bs =>
        val n = bs.size
        bs -> Array.tabulate(bs.points): i =>
          val x = i % n
          val y = i / n
          List(
            Option.when(x > 0)(i - 1),
            Option.when(x < n - 1)(i + 1),
            Option.when(y > 0)(i - n),
            Option.when(y < n - 1)(i + n)
          ).flatten.map(Point.fromIndex)
      .toMap

private object Zobrist:
  // deterministic so that hashes are stable between server restarts
  val table: Array[Long] =
    val rng = java.util.SplittableRandom(0x60ba2L)
    Array.fill(19 * 19 * 2)(rng.nextLong())
