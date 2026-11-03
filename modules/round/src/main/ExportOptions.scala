package lila.round

// Options for exporting a game to json
case class ExportOptions(
    movetimes: Boolean = false,
    clocks: Boolean = false,
    blurs: Boolean = false,
    rating: Boolean = true,
    nvui: Boolean = false
)(using val me: Option[Me])

object ExportOptions:
  val default = ExportOptions()(using none)
