package lila.game

import chess.variant.Variant
import chess.{ ByColor, Clock, Color, Ply, Rated, Status }
import reactivemongo.api.bson.*
import scalalib.model.Days

import scala.util.{ Success, Try }

import lila.core.game.{
  ClockHistory,
  Game,
  GameDrawOffers,
  GameMetadata,
  GameRule,
  LightGame,
  LightPlayer,
  PgnImport,
  Source,
  emptyDrawOffers
}
import lila.db.BSON
import lila.db.dsl.{ *, given }

object BSONHandlers:

  import lila.db.ByteArray.byteArrayHandler
  import lila.game.Game.maxPlies

  given statusHandler: BSONHandler[Status] = tryHandler[Status](
    { case BSONInteger(v) => Status(v).toTry(s"No such status: $v") },
    x => BSONInteger(x.id)
  )

  given BSONHandler[GameRule] = valueMapHandler[String, GameRule](GameRule.byKey)(_.toString)

  given sourceHandler: BSONHandler[Source] = valueMapHandler[Int, Source](Source.byId)(_.id)

  private[game] given gameDrawOffersHandler: BSONHandler[GameDrawOffers] = tryHandler[GameDrawOffers](
    { case arr: BSONArray =>
      Success(arr.values.foldLeft(emptyDrawOffers) {
        case (offers, BSONInteger(p)) =>
          if p > 0 then offers.copy(white = offers.white.incl(Ply(p)))
          else offers.copy(black = offers.black.incl(Ply(-p)))
        case (offers, _) => offers
      })
    },
    offers =>
      BSONArray(
        (Ply.raw(offers.white) ++ Ply.raw(offers.black).map(-_)).view.map(BSONInteger.apply).toIndexedSeq
      )
  )

  given BSONDocumentHandler[PgnImport] = Macros.handler

  given gameHandler: BSON[Game] with
    import lila.game.Game.BSONFields as F

    def reads(r: BSON.Reader): Game =

      lila.mon.game.fetch.increment()

      val playerIds = r.str(F.playerIds)
      val light = lightGameReader.reads(r)

      val startedAtPly = Ply(r.intD(F.startedAtTurn))
      val ply = r.get[Ply](F.turns).atMost(maxPlies) // unlimited can cause StackOverflowError
      val turnColor = ply.turn
      val createdAt = r.date(F.createdAt)

      val playedPlies = ply - startedAtPly

      val whitePlayer = Player.from(light, Color.white, playerIds, r.getD[Bdoc](F.whitePlayer))
      val blackPlayer = Player.from(light, Color.black, playerIds, r.getD[Bdoc](F.blackPlayer))

      val size = goban.BoardSize.orDefault(r.intD(F.boardSize))
      val komi = r.intO(F.komi).fold(size.defaultKomi)(goban.Komi.fromTenths)
      val handicap = r.intD(F.handicap)
      val moves = goban.Game.parseMoves(r.strD(F.goMoves), size).take(playedPlies.value)
      val replayed = goban.Game
        .replay(size, komi, handicap, moves)
        .fold(err => sys.error(s"Invalid go game ${light.id}: $err"), identity)

      val goGame = replayed.copy(
        clock = r
          .getO[Color => Clock](F.clock)(using
            clockBSONReader(createdAt, whitePlayer.berserk, blackPlayer.berserk)
          )
          .map(_(turnColor)),
        ply = ply,
        startedAtPly = startedAtPly
      )

      val whiteClockHistory = r.bytesO(F.whiteClockHistory)
      val blackClockHistory = r.bytesO(F.blackClockHistory)

      Game(
        id = light.id,
        players = ByColor(whitePlayer, blackPlayer),
        go = goGame,
        loadClockHistory = clk =>
          for
            bw <- whiteClockHistory
            bb <- blackClockHistory
            history <-
              BinaryFormat.clockHistory
                .read(clk.limit, bw, bb, (light.status == Status.Outoftime).option(turnColor))
            _ = lila.mon.game.loadClockHistory.increment()
          yield history,
        status = light.status,
        daysPerTurn = r.getO[Days](F.daysPerTurn),
        binaryMoveTimes = r.getO[Array[Byte]](F.moveTimes),
        rated = r.yesnoD(F.rated),
        bookmarks = r.intD(F.bookmarks),
        createdAt = createdAt,
        movedAt = r.dateD(F.movedAt, createdAt),
        metadata = GameMetadata(
          source = r.getO[Source](F.source),
          pgnImport = r.getO[PgnImport](F.pgnImport),
          tournamentId = r.getO[TourId](F.tournamentId),
          swissId = r.getO[SwissId](F.swissId),
          simulId = r.getO[SimulId](F.simulId),
          analysed = r.boolD(F.analysed),
          drawOffers = r.getD(F.drawOffers, emptyDrawOffers),
          rules = r.getD(F.rules, Set.empty)
        ),
        abortedBy = r.getO[Color](F.abortedBy),
        scoring = lila.core.game.GoScoring(
          dead = r.intsD(F.dead).map(goban.Point.fromIndex).toSet,
          accepted = r.getD[List[Color]](F.scoringAccepted, Nil).toSet
        )
      )

    def writes(w: BSON.Writer, o: Game) =
      BSONDocument(
        F.id -> o.id,
        F.playerIds -> o.players.reduce(_.id.value + _.id.value),
        F.playerUids -> o.players
          .map(_.userId)
          .toPair
          .match
            case (None, None) => None
            case (Some(w), None) => Some(List(w.value))
            case (wo, Some(b)) => Some(List(wo.so(_.value), b.value))
        ,
        F.whitePlayer -> w.docO(Player.playerWrite(o.whitePlayer)),
        F.blackPlayer -> w.docO(Player.playerWrite(o.blackPlayer)),
        F.status -> o.status,
        F.turns -> o.go.ply,
        F.startedAtTurn -> w.intO(o.go.startedAtPly.value),
        F.clock -> o.go.clock.flatMap { c =>
          clockBSONWrite(o.createdAt, c).toOption
        },
        F.daysPerTurn -> o.daysPerTurn,
        F.moveTimes -> o.binaryMoveTimes,
        F.whiteClockHistory -> clockHistory(Color.White, o.clockHistory, o.go.clock, o.flagged),
        F.blackClockHistory -> clockHistory(Color.Black, o.clockHistory, o.go.clock, o.flagged),
        F.rated -> w.yesnoO(o.rated),
        F.goMoves -> o.go.moveKeys,
        F.boardSize -> o.boardSize.size,
        F.komi -> o.komi.tenths,
        F.handicap -> w.intO(o.handicap),
        F.dead -> o.scoring.dead.nonEmpty.option(o.scoring.dead.toList.map(_.index).sorted),
        F.scoringAccepted -> o.scoring.accepted.nonEmpty.option(o.scoring.accepted.toList),
        F.bookmarks -> w.intO(o.bookmarks),
        F.createdAt -> w.date(o.createdAt),
        F.movedAt -> w.date(o.movedAt),
        F.source -> o.metadata.source,
        F.pgnImport -> o.metadata.pgnImport,
        F.tournamentId -> o.metadata.tournamentId,
        F.swissId -> o.metadata.swissId,
        F.simulId -> o.metadata.simulId,
        F.analysed -> w.boolO(o.metadata.analysed),
        F.rules -> o.metadata.nonEmptyRules,
        F.abortedBy -> o.abortedBy
      )


  given lightGameReader: lila.db.BSONReadOnly[LightGame] with

    import lila.game.Game.BSONFields as F

    private val emptyPlayerBuilder = lila.game.LightPlayer.builderRead(emptyBdoc)

    def reads(r: BSON.Reader): LightGame =
      val winC = r.boolO(F.winnerColor).map(Color.fromWhite(_))
      val uids = ~r.getO[List[UserId]](F.playerUids)
      val (whiteUid, blackUid) = (uids.headOption.filter(_.value.nonEmpty), uids.lift(1))
      def makePlayer(field: String, color: Color, uid: Option[UserId]): LightPlayer =
        val builder =
          r.getO[lila.game.LightPlayer.Builder](field)(using
            lila.game.LightPlayer.lightPlayerReader
          ) | emptyPlayerBuilder
        builder(color)(uid)
      LightGame(
        id = r.get[GameId](F.id),
        whitePlayer = makePlayer(F.whitePlayer, Color.White, whiteUid),
        blackPlayer = makePlayer(F.blackPlayer, Color.Black, blackUid),
        status = r.get[Status](F.status),
        win = winC,
        variant = Variant.idOrDefault(r.getO[Variant.Id](F.variant))
      )

  private def clockHistory(
      color: Color,
      clockHistory: Option[ClockHistory],
      clock: Option[Clock],
      flagged: Option[Color]
  ) =
    for
      clk <- clock
      history <- clockHistory
      times = history(color)
    yield BinaryFormat.clockHistory.writeSide(clk.limit, times, flagged.has(color))

  private[game] def clockBSONReader(since: Instant, whiteBerserk: Boolean, blackBerserk: Boolean) =
    new BSONReader[Color => Clock]:
      def readTry(bson: BSONValue): Try[Color => Clock] =
        bson match
          case bin: BSONBinary =>
            byteArrayHandler.readTry(bin).map { cl =>
              BinaryFormat.clock(since).read(cl, whiteBerserk, blackBerserk)
            }
          case b => lila.db.BSON.handlerBadType(b)

  private[game] def clockBSONWrite(since: Instant, clock: Clock) =
    byteArrayHandler.writeTry:
      BinaryFormat.clock(since).write(clock)
