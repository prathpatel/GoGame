package goban

import chess.{ ByColor, Color }

/** Area (Chinese) scoring: stones on the board plus surrounded empty points,
  * after removing the stones both players agreed are dead. Komi goes to White.
  */
case class Score(
    stones: ByColor[Int],
    territory: ByColor[Int],
    /** captured dead stones, by the color that captured them */
    deadCaptured: ByColor[Int],
    komi: Komi,
    /** points owned by each color, for display */
    owner: Map[Point, Color]
):
  def total(c: Color): Double =
    stones(c) + territory(c) + (if c.white then komi.value else 0d)

  def black: Double = total(Color.Black)
  def white: Double = total(Color.White)

  def winner: Option[Color] =
    if black > white then Some(Color.Black)
    else if white > black then Some(Color.White)
    else None

  def margin: Double = math.abs(black - white)

  /** "B+3.5", "W+0.5", or "Jigo". */
  def result: String = winner match
    case Some(c) => s"${if c.black then "B" else "W"}+${Score.fmt(margin)}"
    case None => "Jigo"

object Score:
  def fmt(d: Double): String = if d == d.floor then d.toInt.toString else d.toString

object Scoring:

  def area(board: Board, dead: Set[Point], komi: Komi): Score =
    val deadCaptured = ByColor: c =>
      dead.count(p => board.colorAt(p).contains(!c))
    val live = board.remove(dead)
    val owner = scala.collection.mutable.Map.empty[Point, Color]
    live.allPoints.foreach: p =>
      live.colorAt(p).foreach(owner(p) = _)
    val visited = scala.collection.mutable.Set.empty[Point]
    var territory = ByColor(0, 0)
    live.allPoints.foreach: start =>
      if live.isEmpty(start) && !visited.contains(start) then
        // flood fill the empty region and look at what borders it
        val region = scala.collection.mutable.ListBuffer.empty[Point]
        var borders = Set.empty[Color]
        var frontier = List(start)
        visited += start
        while frontier.nonEmpty do
          val cur = frontier.head
          frontier = frontier.tail
          region += cur
          live
            .neighbours(cur)
            .foreach: n =>
              live.colorAt(n) match
                case Some(c) => borders += c
                case None =>
                  if !visited.contains(n) then
                    visited += n
                    frontier = n :: frontier
        if borders.size == 1 then
          val c = borders.head
          territory = territory.update(c, _ + region.size)
          region.foreach(owner(_) = c)
    Score(
      stones = ByColor(live.stoneCount),
      territory = territory,
      deadCaptured = deadCaptured,
      komi = komi,
      owner = owner.toMap
    )

  /** Toggle the life status of the whole group at `p` (used in the scoring phase). */
  def toggleDead(board: Board, dead: Set[Point], p: Point): Set[Point] =
    if board.isEmpty(p) then dead
    else
      val stones = board.group(p).stones
      if dead.contains(p) then dead -- stones else dead ++ stones
