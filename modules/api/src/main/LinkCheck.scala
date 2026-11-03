package lila.api

import lila.chat.UserLine
import lila.core.chat.PublicSource
import lila.core.config.NetDomain

/* Determine if a link can be posted in a public chat.
 * There are no tournaments, studies or teams in this fork, so only spammy
 * messages containing several links to the site are refused.
 */
final private class LinkCheck(domain: NetDomain)(using Executor):

  def apply(line: UserLine, source: PublicSource): Fu[Boolean] =
    fuccess(!multipleLinks.find(line.text))

  private val multipleLinks = s"(?i)$domain.+$domain".r.unanchored
