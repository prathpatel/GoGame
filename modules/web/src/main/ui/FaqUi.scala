package lila.web
package ui

import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

val sourceUrl = "https://github.com/prathpatel/GoGame"

/** The FAQ and About pages, written for Go. */
final class FaqUi(helpers: Helpers, sitePages: SitePages, siteName: String)(
    standardRankableDeviation: Int,
    variantRankableDeviation: Int
):
  import helpers.{ given, * }

  private def question(id: String, title: String, answer: Frag*) =
    details(
      st.id := id,
      cls := "question",
      name := "faq"
    )(
      summary(span(title)),
      div(cls := "answer")(answer)
    )

  def apply(using Context) =
    sitePages
      .SitePage(
        title = "Frequently Asked Questions",
        active = "faq"
      )
      .css("bits.faq"):
        div(cls := "faq box box-pad")(
          h1(cls := "box__top")("Frequently Asked Questions"),
          h2("Playing"),
          question(
            "rules",
            "What are the rules?",
            p(
              "Players take turns placing a stone on an empty intersection, Black first. ",
              "A stone or a group of connected stones is captured when it has no empty neighbouring point ",
              "(no liberty) left."
            ),
            p(
              "You may not play a move that leaves your own group without liberties (suicide), ",
              "unless it captures. You may not recreate a previous board position (positional superko), ",
              "which settles every ko fight."
            ),
            p(
              "New to Go? The ",
              a(href := "https://en.wikipedia.org/wiki/Rules_of_Go", targetBlank)("Rules of Go"),
              " article is a good start."
            )
          ),
          question(
            "end",
            "How does a game end?",
            p("A game ends when a player resigns, runs out of time, or when both players pass in a row."),
            p(
              "After two passes, the game enters the counting phase: the server proposes which stones are dead. ",
              "Click a group to mark it dead or alive. When both players accept, the game is scored. ",
              "If you disagree about the position, choose ",
              em("Resume play"),
              " and play it out."
            )
          ),
          question(
            "scoring",
            "How is the score counted?",
            p(
              "With area scoring (Chinese rules): each player gets one point per stone on the board and one ",
              "point per empty intersection surrounded only by their stones. Dead stones are removed first."
            ),
            p(
              "White receives komi to compensate for moving second: 7.5 points in even games, 0.5 points in ",
              "handicap games. The half point means there are no draws."
            )
          ),
          question(
            "handicap",
            "What is a handicap game?",
            p(
              "When challenging a friend or the computer you can give Black 2 to 9 stones (up to 5 on 9×9), ",
              "placed on the traditional star points. White then moves first. Handicap games are never rated."
            )
          ),
          question(
            "boards",
            "Which board should I pick?",
            p(
              "9×9 games are quick and tactical: a good place to start. 13×13 introduces strategy, and 19×19 ",
              "is the full game. Quick pairing in the lobby is for 19×19; custom seeks can use any size."
            )
          ),
          question(
            "computer",
            "How strong is the computer?",
            p(
              "Levels 1 to 8 use a built-in Monte-Carlo player: friendly for beginners, weak for experienced ",
              "players. Servers can also connect ",
              a(href := "https://github.com/lightvector/KataGo", targetBlank)("KataGo"),
              ", one of the strongest Go programs, for the higher levels."
            )
          ),
          question(
            "takeback",
            "Can I take back a move?",
            p(
              "Yes, if your opponent agrees, or always against the computer. You can disable takebacks in your ",
              "preferences."
            )
          ),
          h2("Ratings"),
          question(
            "rating-system",
            "What rating system is used?",
            p(
              "Ratings use the Glicko-2 system, separately for each time control: bullet, blitz, rapid, ",
              "classical and correspondence. New players start at 1500."
            )
          ),
          question(
            "provisional",
            "Why is there a question mark next to a rating?",
            p(
              "The rating is provisional: it is based on too few games to be reliable. It stays provisional ",
              s"while the rating deviation is above $standardRankableDeviation."
            )
          ),
          question(
            "hide-ratings",
            "How do I hide ratings while playing?",
            p("Open your preferences, then Display, and turn off ratings.")
          ),
          h2("This site"),
          question(
            "lichess",
            "Is this Lichess?",
            p(
              strong(siteName),
              " is an independent Go server built from the source code of ",
              a(href := "https://lichess.org", targetBlank)("Lichess"),
              ", the free chess server. It is not run by or affiliated with Lichess."
            )
          ),
          question(
            "source",
            "Where is the source code?",
            p(
              "The server is free software under the GNU Affero General Public License. The code is at ",
              a(href := sourceUrl, targetBlank)(sourceUrl),
              "."
            )
          ),
          question(
            "sgf",
            "Can I download my games?",
            p("Yes: finished games have a ", em("Download SGF"), " link, readable by any Go software.")
          )
        )

  def about(using Context) =
    sitePages
      .SitePage(
        title = s"About $siteName",
        active = "about",
        contentCls = "page box box-pad"
      )
      .css("bits.page"):
        frag(
          h1(cls := "box__top")(s"About $siteName"),
          div(cls := "body")(
            p(
              strong(siteName),
              " is a free, open source server for the game of Go, also called Baduk or Weiqi. ",
              "There are no ads and no paid features."
            ),
            p(
              "Play 9×9, 13×13 and 19×19 games in real time or by correspondence, against people from the ",
              "lobby, against a friend with a challenge link, or against the computer. Rated games use ",
              "Glicko-2 ratings, and every game can be downloaded as SGF."
            ),
            p(
              "The server is built from the source code of ",
              a(href := "https://lichess.org", targetBlank)("Lichess"),
              " (",
              a(href := "https://github.com/lichess-org/lila", targetBlank)("lila"),
              " and ",
              a(href := "https://github.com/lichess-org/lila-ws", targetBlank)("lila-ws"),
              "), with chess replaced by Go. Like Lichess, it is licensed under the GNU Affero General ",
              "Public License: its ",
              a(href := sourceUrl, targetBlank)("source code"),
              " is available to everyone. It is not affiliated with Lichess."
            )
          )
        )
