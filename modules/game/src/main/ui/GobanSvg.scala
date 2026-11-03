package lila.game
package ui

import lila.ui.ScalatagsTemplate.*

/** Server-side SVG rendering of a Go board, used for game thumbnails. */
object GobanSvg:

  private val cell = 10
  private val margin = 6

  def apply(board: String, size: Int, lastMove: Option[String] = None): Frag =
    raw(render(board, size, lastMove))

  def render(board: String, size: Int, lastMove: Option[String]): String =
    val full = margin * 2 + cell * (size - 1)
    val sb = StringBuilder()
    sb ++= s"""<svg class="goban-mini" viewBox="0 0 $full $full" xmlns="http://www.w3.org/2000/svg">"""
    sb ++= s"""<rect class="goban-mini__bg" width="$full" height="$full"/>"""
    sb ++= """<g class="goban-mini__lines">"""
    (0 until size).foreach: i =>
      val c = margin + i * cell
      val end = margin + (size - 1) * cell
      sb ++= s"""<line x1="$margin" y1="$c" x2="$end" y2="$c"/><line x1="$c" y1="$margin" x2="$c" y2="$end"/>"""
    sb ++= "</g>"
    goban.BoardSize(size).foreach: bs =>
      bs.starPoints.foreach: p =>
        sb ++= s"""<circle class="goban-mini__star" cx="${margin + p.x(size) * cell}" cy="${margin + p.y(size) * cell}" r="1.2"/>"""
    val last = lastMove.flatMap(goban.Point.fromSgf(_, size)).map(_.index)
    board.iterator.zipWithIndex.foreach: (ch, i) =>
      if ch == 'b' || ch == 'w' then
        val cx = margin + (i % size) * cell
        val cy = margin + (i / size) * cell
        val color = if ch == 'b' then "black" else "white"
        sb ++= s"""<circle class="goban-mini__stone $color" cx="$cx" cy="$cy" r="4.6"/>"""
        if last.contains(i) then
          sb ++= s"""<circle class="goban-mini__last $color" cx="$cx" cy="$cy" r="2"/>"""
    sb ++= "</svg>"
    sb.toString
