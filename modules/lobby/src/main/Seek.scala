package lila.lobby

import chess.rating.RatingProvisional
import chess.{ IntRating, Rated, Speed }
import play.api.libs.json.*
import scalalib.ThreadLocalRandom
import scalalib.model.Days

import lila.common.Json.given
import lila.core.perf.UserWithPerfs
import lila.core.rating.RatingRange
import lila.rating.PerfType

// correspondence chess, persistent
case class Seek(
    _id: String,
    size: Int,
    daysPerTurn: Option[Days],
    rated: Rated,
    user: LobbyUser,
    ratingRange: RatingRange,
    createdAt: Instant
):
  inline def id = _id

  val boardSize = goban.BoardSize.orDefault(size)

  def compatibleWith(h: Seek) =
    user.id != h.user.id &&
      compatibilityProperties == h.compatibilityProperties &&
      ratingRangeCompatibleWith(h) && h.ratingRangeCompatibleWith(this)

  private def ratingRangeCompatibleWith(s: Seek) =
    realRatingRange.forall(_.contains(s.rating))

  private def compatibilityProperties = (size, rated, daysPerTurn)

  lazy val realRatingRange: Option[RatingRange] = ratingRange.ifNotDefault

  lazy val perfType = PerfType(chess.variant.Standard, Speed.Correspondence)

  def perf = user.perfAt(perfType)
  def rating = perf.rating

  def render: JsObject =
    Json
      .obj(
        "id" -> _id,
        "username" -> user.username,
        "rating" -> rating,
        "size" -> size,
        "perf" -> Json.obj("key" -> perfType.key),
        "mode" -> rated.id // must keep BC
      )
      .add("days" -> daysPerTurn)
      .add("provisional" -> perf.provisional.yes)

object Seek:

  given UserIdOf[Seek] = _.user.id

  val idSize = 8
  def makeId = ThreadLocalRandom.nextString(idSize)

  def make(
      size: goban.BoardSize,
      daysPerTurn: Option[Days],
      rated: Rated,
      user: UserWithPerfs,
      ratingRange: RatingRange,
      blocking: lila.core.pool.Blocking
  ): Seek = Seek(
    _id = makeId,
    size = size.size,
    daysPerTurn = daysPerTurn,
    rated = rated,
    user = LobbyUser.make(user, blocking),
    ratingRange = ratingRange,
    createdAt = nowInstant
  )

  def renew(seek: Seek) = Seek(
    _id = makeId,
    size = seek.size,
    daysPerTurn = seek.daysPerTurn,
    rated = seek.rated,
    user = seek.user,
    ratingRange = seek.ratingRange,
    createdAt = nowInstant
  )

  import reactivemongo.api.bson.*
  import lila.db.dsl.{ *, given }
  private given BSONHandler[RatingRange] = tryHandler[RatingRange](
    { case BSONString(s) => RatingRange.parse(s).toTry(s"Invalid rating range: $s") },
    r => BSONString(r.toString)
  )
  given BSONHandler[LobbyPerf] = BSONIntegerHandler.as[LobbyPerf](
    b => LobbyPerf(IntRating(b.abs), RatingProvisional(b < 0)),
    x => x.rating.value * (if x.provisional.yes then -1 else 1)
  )
  private given BSONHandler[Map[PerfKey, LobbyPerf]] = typedMapHandlerIso[PerfKey, LobbyPerf]
  private[lobby] given BSONDocumentHandler[LobbyUser] = Macros.handler
  private[lobby] given BSONDocumentHandler[Seek] = Macros.handler
