package goban

import chess.Color

/** Minimal SGF (FF[4]) support: export a game, import the main line of a record. */
object Sgf:

  case class Tags(
      black: Option[String] = None,
      white: Option[String] = None,
      blackRank: Option[String] = None,
      whiteRank: Option[String] = None,
      date: Option[String] = None,
      result: Option[String] = None,
      event: Option[String] = None,
      site: Option[String] = None,
      timeControl: Option[String] = None
  )

  private def esc(s: String) = s.replace("\\", "\\\\").replace("]", "\\]")

  def write(game: Game, tags: Tags = Tags()): String =
    val n = game.board.width
    val header = List(
      Some("GM[1]FF[4]CA[UTF-8]AP[lila-go]RU[Chinese]"),
      Some(s"SZ[$n]"),
      Some(s"KM[${Score.fmt(game.komi.value)}]"),
      Option.when(game.handicap > 0)(s"HA[${game.handicap}]"),
      Option
        .when(game.handicap > 0)(Handicap.points(game.size, game.handicap))
        .filter(_.nonEmpty)
        .map(ps => "AB" + ps.map(p => s"[${p.sgf(n)}]").mkString),
      tags.black.map(v => s"PB[${esc(v)}]"),
      tags.white.map(v => s"PW[${esc(v)}]"),
      tags.blackRank.map(v => s"BR[${esc(v)}]"),
      tags.whiteRank.map(v => s"WR[${esc(v)}]"),
      tags.date.map(v => s"DT[${esc(v)}]"),
      tags.event.map(v => s"EV[${esc(v)}]"),
      tags.site.map(v => s"PC[${esc(v)}]"),
      tags.timeControl.map(v => s"TM[${esc(v)}]"),
      tags.result.map(v => s"RE[${esc(v)}]")
    ).flatten.mkString
    val firstColor = Game.colorToMoveAt(game.startedAtPly)
    val moves = game.moves.zipWithIndex.map: (m, i) =>
      val c = if i % 2 == 0 then firstColor else !firstColor
      val tag = if c.black then "B" else "W"
      m match
        case Move.Pass => s";$tag[]"
        case Move.Play(p) => s";$tag[${p.sgf(n)}]"
    s"(;$header${moves.mkString})\n"

  case class Parsed(
      size: BoardSize,
      komi: Komi,
      handicap: Int,
      moves: List[Move],
      tags: Tags
  )

  private val propRegex = """([A-Z]{1,2})((?:\[(?:\\.|[^\]\\])*\])+)""".r
  private val valueRegex = """\[((?:\\.|[^\]\\])*)\]""".r

  /** Parse the main line (first variation at every fork) of an SGF record. */
  def read(sgf: String): Either[String, Parsed] =
    val mainLine = firstVariation(sgf)
    val nodes = mainLine.split(';').toList.drop(1)
    if nodes.isEmpty then Left("Not an SGF record")
    else
      val props: List[List[(String, List[String])]] = nodes.map: node =>
        propRegex
          .findAllMatchIn(node)
          .map: m =>
            m.group(1) -> valueRegex.findAllMatchIn(m.group(2)).map(_.group(1).replace("\\]", "]")).toList
          .toList
      val root = props.head.toMap
      val sizeInt = root.get("SZ").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(19)
      BoardSize(sizeInt).toRight(s"Unsupported board size $sizeInt").map: size =>
        val komi = root.get("KM").flatMap(_.headOption).flatMap(_.toDoubleOption).map(Komi(_)).getOrElse(size.defaultKomi)
        val handicap = root.get("HA").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(0)
        val moves = props.flatMap(_.collect {
          case ("B" | "W", v :: _) => Move.parse(v, size.size).getOrElse(Move.Pass)
          case ("B" | "W", Nil) => Move.Pass
        })
        def tag(k: String) = root.get(k).flatMap(_.headOption).filter(_.nonEmpty)
        Parsed(
          size,
          komi,
          handicap,
          moves,
          Tags(
            black = tag("PB"),
            white = tag("PW"),
            blackRank = tag("BR"),
            whiteRank = tag("WR"),
            date = tag("DT"),
            result = tag("RE"),
            event = tag("EV")
          )
        )

  /** Strip all variations except the first one at each branching point. */
  private def firstVariation(sgf: String): String =
    val sb = StringBuilder()
    var depth = 0
    var skipDepth = -1 // when >= 0, we are skipping a sibling variation opened at that depth
    var closedAtDepth = Set.empty[Int] // depths where the first variation already closed
    var inValue = false
    var i = 0
    while i < sgf.length do
      val ch = sgf.charAt(i)
      if inValue then
        if skipDepth < 0 then sb += ch
        if ch == '\\' && i + 1 < sgf.length then
          if skipDepth < 0 then sb += sgf.charAt(i + 1)
          i += 1
        else if ch == ']' then inValue = false
      else
        ch match
          case '[' =>
            inValue = true
            if skipDepth < 0 then sb += ch
          case '(' =>
            depth += 1
            if skipDepth < 0 && closedAtDepth.contains(depth) then skipDepth = depth
          case ')' =>
            if skipDepth == depth then skipDepth = -1
            else if skipDepth < 0 then closedAtDepth += depth
            depth -= 1
          case c =>
            if skipDepth < 0 then sb += c
      i += 1
    sb.toString

  def colorTag(c: Color) = if c.black then "B" else "W"
