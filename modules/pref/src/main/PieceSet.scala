package lila.pref

import play.api.libs.json.*

import lila.common.Json.given

opaque type Featured = Boolean
object Featured extends YesNo[Featured]

case class PieceSet private[pref] (name: String, featured: Featured = Featured.No):

  override def toString = name

sealed trait PieceSetObject:
  given Writes[PieceSet] = Json.writes[PieceSet]

  def default = all.head
  val all: List[PieceSet]

  lazy val allByName = all.mapBy(_.name)

  def get(name: String): PieceSet = allByName.getOrElse(name, default)
  def get(name: Option[String]): PieceSet = name.fold(default)(get)

  def contains(name: String) = allByName contains name

/** Go stone styles, drawn with SVG gradients, see ui/lib/css/go/_goban.scss. */
object PieceSet extends PieceSetObject:

  val all = List(
    PieceSet("shell-slate", Featured.Yes),
    PieceSet("yunzi", Featured.Yes),
    PieceSet("flat", Featured.Yes),
    PieceSet("glass", Featured.Yes),
    PieceSet("outline", Featured.Yes)
  )

object PieceSet3d extends PieceSetObject:

  val all = List(
    PieceSet("Basic", Featured.Yes),
    PieceSet("Wood", Featured.Yes),
    PieceSet("Metal"),
    PieceSet("RedVBlue", Featured.Yes),
    PieceSet("ModernJade"),
    PieceSet("ModernWood", Featured.Yes),
    PieceSet("Glass"),
    PieceSet("Trimmed", Featured.Yes),
    PieceSet("Experimental", Featured.Yes),
    PieceSet("Staunton", Featured.Yes),
    PieceSet("CubesAndPi", Featured.Yes)
  )
