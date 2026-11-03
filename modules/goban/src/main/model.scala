package goban

import chess.Color

/** Board sizes supported by the server. The board size plays the role that
  * chess variants play in lila: it changes the game, the rating pool, and the UI.
  */
enum BoardSize(val size: Int, val key: String, val name: String):
  case Nine extends BoardSize(9, "9x9", "9×9")
  case Thirteen extends BoardSize(13, "13x13", "13×13")
  case Nineteen extends BoardSize(19, "19x19", "19×19")

  def points: Int = size * size

  /** Default komi for an even game (area scoring). */
  def defaultKomi: Komi = Komi(7.5)

  /** Star points (hoshi) for rendering. */
  def starPoints: List[Point] =
    val edge = if size < 13 then 2 else 3
    val far = size - 1 - edge
    val mid = size / 2
    val corners = List(Point(edge, edge, size), Point(far, edge, size), Point(edge, far, size), Point(far, far, size))
    if size == 9 then Point(mid, mid, size) :: corners
    else corners ++ List(mid -> edge, edge -> mid, far -> mid, mid -> far, mid -> mid).map(Point(_, _, size))

object BoardSize:
  val all = values.toList
  val default = Nineteen
  def apply(size: Int): Option[BoardSize] = all.find(_.size == size)
  def orDefault(size: Int): BoardSize = apply(size).getOrElse(default)
  def byKey(key: String): Option[BoardSize] = all.find(_.key == key)

/** Half points of komi are stored as tenths to keep everything integral. */
opaque type Komi = Double
object Komi:
  def apply(d: Double): Komi = (math.round(d * 2) / 2d)
  val zero: Komi = 0d
  extension (k: Komi)
    def value: Double = k
    def tenths: Int = math.round(k * 10).toInt
  def fromTenths(t: Int): Komi = Komi(t / 10d)

/** A point on the board, stored as its linear index (y * size + x).
  * x grows to the right, y grows downwards; (0,0) is the top left corner.
  */
opaque type Point = Int
object Point:
  def apply(x: Int, y: Int, size: Int): Point = y * size + x
  def fromIndex(i: Int): Point = i
  extension (p: Point)
    inline def index: Int = p
    def x(size: Int): Int = p % size
    def y(size: Int): Int = p / size

    /** SGF coordinates: "aa" is the top left corner. */
    def sgf(size: Int): String =
      s"${('a' + p % size).toChar}${('a' + p / size).toChar}"

    /** Human coordinates, as printed on most boards: columns A-T without I, rows from the bottom. */
    def human(size: Int): String =
      val col = "ABCDEFGHJKLMNOPQRSTUVWXYZ".charAt(p % size)
      s"$col${size - p / size}"

  def fromSgf(str: String, size: Int): Option[Point] =
    if str.length != 2 then None
    else
      val x = str.charAt(0) - 'a'
      val y = str.charAt(1) - 'a'
      Option.when(x >= 0 && y >= 0 && x < size && y < size)(Point(x, y, size))

  def fromHuman(str: String, size: Int): Option[Point] =
    val s = str.trim.toUpperCase
    if s.length < 2 then None
    else
      val x = "ABCDEFGHJKLMNOPQRSTUVWXYZ".indexOf(s.charAt(0))
      s.drop(1).toIntOption.flatMap: row =>
        val y = size - row
        Option.when(x >= 0 && x < size && y >= 0 && y < size)(Point(x, y, size))

/** A move in Go: either placing a stone or passing. */
enum Move:
  case Play(point: Point)
  case Pass

  def isPass = this == Move.Pass

  /** Compact wire/storage form: SGF coordinates, or "pass". */
  def key(size: Int): String = this match
    case Play(p) => p.sgf(size)
    case Pass => Move.passKey

  def human(size: Int): String = this match
    case Play(p) => p.human(size)
    case Pass => "pass"

object Move:
  val passKey = "pass"
  def parse(str: String, size: Int): Option[Move] =
    if str == passKey || str == "tt" || str.isEmpty then Some(Pass)
    else Point.fromSgf(str, size).orElse(Point.fromHuman(str, size)).map(Play(_))

/** Stone colors map onto chess colors so lila's player/clock machinery can be reused.
  * Black plays first in Go; lila's `Color.Black` is the black stones.
  */
object Stone:
  val empty: Byte = 0
  val black: Byte = 1
  val white: Byte = 2
  def of(c: Color): Byte = if c.black then black else white
  def color(b: Byte): Option[Color] =
    if b == black then Some(Color.Black) else if b == white then Some(Color.White) else None
  def char(b: Byte): Char = if b == black then 'b' else if b == white then 'w' else '.'
