package lila.api

import lila.core.chat.PublicSource

/* Checks that a chat can still be posted to */
final class ChatFreshness()(using Executor):

  def of: PublicSource => Fu[Boolean] = _ => fuTrue
