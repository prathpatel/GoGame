package lila.report

import lila.core.game.{ GameApi, GameRepo }

/** Lichess requests engine analysis of reported games. There is no analysis
  * engine for Go yet, so reports are left to human moderators.
  */
final class AutoAnalysis(gameRepo: GameRepo, gameApi: GameApi)(using ec: Executor, scheduler: Scheduler):

  def apply(candidate: Report.Candidate): Funit = funit
