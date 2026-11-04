package lila.round

import chess.{ Centis, ErrorStr, MoveMetrics, Ply }

import java.util.concurrent.TimeUnit

import lila.common.Bus
import lila.core.round.*
import lila.game.GameExt.{ applyMove, updateScoring }
import lila.game.Progress
import lila.game.actorApi.MoveGameEvent
import lila.round.RoundGame.*

final private class MovePlayer(
    finisher: Finisher,
    scheduleExpiration: ScheduleExpiration,
    aiEngine: AiEngine
)(using Executor, Scheduler):

  sealed private trait MoveResult
  private case object Flagged extends MoveResult
  private case class MoveApplied(progress: Progress, move: goban.Move, compedLag: Option[Centis])
      extends MoveResult

  private[round] def human(play: HumanPlay, round: RoundAsyncActor)(
      pov: Pov
  )(using proxy: GameProxy): Fu[Events] =
    import pov.{ game, color }
    if game.ply > lila.game.Game.maxPlies then
      round ! TooManyPlies
      fuccess(Nil)
    else if game.inScoringPhase then fufail(ClientError(s"$pov game is being scored"))
    else if game.playableBy(color) then
      goban.Move
        .parse(play.key, game.board.width)
        .toRight(chess.ErrorStr(s"invalid move ${play.key}"))
        .flatMap(applyMove(game, _, play.blur, play.moveMetrics))
        .leftMap(e => s"$pov $e")
        .fold(errs => fufail(ClientError(errs)), fuccess)
        .flatMap:
          case Flagged => finisher.outOfTime(game)
          case MoveApplied(progress, move, compedLag) =>
            compedLag.foreach: lag =>
              lila.mon.round.move.lag.moveComp.record(lag.millis, TimeUnit.MILLISECONDS)
            proxy.save(progress) >>
              postHumanPlay(round, pov, progress, move)
    else if game.finished then fufail(GameIsFinishedError(game.id))
    else if game.aborted then fufail(ClientError(s"$pov game is aborted"))
    else if !game.turnOf(color) then fufail(ClientError(s"$pov not your turn"))
    else fufail(ClientError(s"$pov move refused for some reason"))

  private def postHumanPlay(
      round: RoundAsyncActor,
      pov: Pov,
      progress: Progress,
      move: goban.Move
  )(using GameProxy): Fu[Events] =
    notifyMove(move, progress.game)
    if progress.game.inScoringPhase then
      // both players passed: suggest dead stones to start the counting phase
      startScoring(progress.game).dmap(progress.events ::: _)
    else
      if progress.game.playableByAi then requestAi(progress.game, round)
      if pov.opponent.isProposingTakeback then round ! RoundBus.Takeback(pov.player.id, false)
      scheduleExpiration.exec(progress.game)
      fuccess(progress.events)

  private def startScoring(game: Game)(using proxy: GameProxy): Fu[Events] =
    for
      dead <- aiEngine.deadStones(game.go)
      progress = game.updateScoring(lila.core.game.GoScoring(dead, Set.empty))
      _ <- proxy.save(progress)
    yield progress.events

  /** A move computed by the built-in engine. `ply` guards against stale moves after a takeback. */
  private[round] def ai(game: Game, move: goban.Move, ply: Ply)(using proxy: GameProxy): Fu[Events] =
    if game.playable && game.player.isAi && game.ply == ply && !game.inScoringPhase then
      applyMove(game, move, blur = false, metrics = aiLag)
        .fold(errs => fufail(AiError(ErrorStr.raw(errs))), fuccess)
        .flatMap:
          case Flagged => finisher.outOfTime(game)
          case MoveApplied(progress, move, _) =>
            for
              _ <- proxy.save(progress)
              _ = notifyMove(move, progress.game)
              events <-
                if progress.game.inScoringPhase then
                  // the AI agrees with the estimated dead stones straight away
                  for
                    dead <- aiEngine.deadStones(progress.game.go)
                    p2 = progress.game.updateScoring(lila.core.game.GoScoring(dead, Set(game.turnColor)))
                    _ <- proxy.save(p2)
                  yield progress.events ::: p2.events
                else fuccess(progress.events)
            yield events
    else fufail(AiError(s"Not AI turn move: $move id: ${game.id} ply: ${game.ply} expected: $ply"))

  private[round] def requestAi(game: Game, round: RoundAsyncActor): Unit =
    game.playableByAi.so:
      if game.ply > lila.game.Game.maxPlies then round ! ResignAi
      else
        val level = game.aiLevel | 1
        val snapshot = game.go
        val ply = game.ply
        // give the human a moment to see their own move
        lila.common.LilaFuture
          .delay(300.millis)(aiEngine.move(snapshot, level))
          .recover: e =>
            // never leave the game waiting: the built-in bot answers instead
            logger.warn(s"AI move failed in ${game.id} at ply $ply (${e.getMessage}), the built-in bot plays")
            AiEngine.Choice.Play(goban.Bot.move(snapshot, level).move)
          .foreach:
            case AiEngine.Choice.Play(move) =>
              lila.mon.fishnet.move(level).increment()
              round ! RoundBus.AiPlay(move, ply)
            case AiEngine.Choice.Resign => round ! RoundAsyncActor.AiResign(ply)
        // if no move arrived by then (a lost request, a stuck engine), ask again
        summon[Scheduler].scheduleOnce(aiEngine.watchdogDelay):
          round ! RoundAsyncActor.AiCheck(ply)
        ()

  private val aiLag = MoveMetrics(clientLag = Centis(5).some)

  private def applyMove(
      game: Game,
      move: goban.Move,
      blur: Boolean,
      metrics: MoveMetrics
  ): Either[ErrorStr, MoveResult] =
    game.go
      .apply(move, metrics)
      .map:
        case ncg if ncg.value.clock.exists(_.outOfTime(game.turnColor, withGrace = false)) => Flagged
        case ncg =>
          MoveApplied(
            game.applyMove(ncg.value, move, blur),
            move,
            ncg.compensated
          )

  private def notifyMove(move: goban.Move, game: Game): Unit =
    import lila.core.round.{ CorresMoveEvent, MoveEvent }
    val color = !game.turnColor
    val moveEvent = MoveEvent(gameId = game.id, move = move.key(game.board.width))

    Bus.publishDyn(MoveGameEvent(game, moveEvent.move), MoveGameEvent.makeChan(game.id))

    // publish correspondence moves
    if game.isCorrespondence && game.nonAi then
      Bus.pub:
        CorresMoveEvent(
          move = moveEvent,
          playerUserId = game.player(color).userId,
          mobilePushable = game.mobilePushable,
          alarmable = game.alarmable,
          unlimited = game.isUnlimited
        )
