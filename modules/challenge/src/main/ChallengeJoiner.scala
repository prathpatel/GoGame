package lila.challenge

import chess.{ ByColor, Rated }

import lila.core.user.GameUser

final private class ChallengeJoiner(
    gameRepo: lila.game.GameRepo,
    userApi: lila.core.user.UserApi,
    onStart: lila.core.game.OnStart
)(using Executor, Scheduler):

  def apply(c: Challenge, destUser: GameUser): FuRaise[String, Pov] = for
    exists <- gameRepo.exists(c.gameId)
    _ <- raiseIf(exists)("The challenge has already been accepted")
    origUser <- c.challengerUserId.so(userApi.byIdWithPerf(_, c.perfType))
    game = ChallengeJoiner.createGame(c, origUser, destUser)
    _ <- gameRepo.insertDenormalized(game)
    _ <- onStartOrRetry(game.id).recover: _ =>
      logger.error(s"onStart failed for game ${game.id}")
  yield Pov(game, !c.finalColor)

  private def onStartOrRetry(id: GameId, retries: Int = 3): Funit =
    onStart
      .exec(id)
      .recoverWith:
        case _ if retries > 0 =>
          logger.warn(s"onStart failed for game $id. Retries left: $retries")
          lila.common.LilaFuture.delay(500.millis)(onStartOrRetry(id, retries - 1))
      .void

private object ChallengeJoiner:

  def createGame(
      c: Challenge,
      origUser: GameUser,
      destUser: GameUser
  ): Game =
    val goGame = goban.Game(
      c.boardSize,
      goban.Game.defaultKomi(c.handicap),
      c.handicap,
      c.timeControl.realTime.map(_.toClock)
    )
    lila.core.game
      .newGame(
        go = goGame,
        players = ByColor: color =>
          lila.game.Player.make(color, if c.finalColor == color then origUser else destUser),
        rated = c.rated.map(_ && c.handicap == 0),
        source = lila.core.game.Source.Friend,
        daysPerTurn = c.daysPerTurn,
        pgnImport = None,
        rules = c.rules
      )
      .withId(c.gameId)
      .start
