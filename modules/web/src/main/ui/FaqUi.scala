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
              "is the full game. Quick pairing offers each size; custom games can use any time control."
            )
          ),
          question(
            "time",
            "How does the clock work?",
            p(
              "Clocks use Fischer timing: a main time, plus an increment added after each of your moves. ",
              "For example, 10+15 is 10 minutes each, plus 15 seconds per move. ",
              "A player makes about 40 moves on 9×9, 80 on 13×13 and 120 to 150 on 19×19, ",
              "so quick pairing gives the bigger boards more time."
            ),
            p(
              "Many Go servers use byo-yomi (a main time, then periods of a fixed number of seconds per move). ",
              "It is not available here yet. Correspondence and unlimited games have no clock pressure at all."
            )
          ),
          question(
            "computer",
            "How strong is the computer?",
            p(
              "The computer is ",
              a(href := "https://github.com/lightvector/KataGo", targetBlank)("KataGo"),
              ", one of the strongest Go programs. Levels 1 to 7 use its human-style network to play like ",
              "a human of a given rank: 20 kyu, 15 kyu, 10 kyu, 5 kyu, 1 kyu, 3 dan and 9 dan. ",
              "Level 8 is KataGo at full strength. Hover over the computer's name in a game to see its rank."
            ),
            p(
              "If KataGo is not available, a much weaker built-in player takes over, and the computer's ",
              "name reads \"Computer level\" instead of \"KataGo\"."
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

  private def legalPage(title: String, active: String)(body: Frag*)(using Context) =
    sitePages
      .SitePage(title = title, active = active, contentCls = "page box box-pad")
      .css("bits.page"):
        frag(
          h1(cls := "box__top")(title),
          div(cls := "body")(body)
        )

  // Plain-language terms for a free Go server. Operators: adapt them to your own service.
  def terms(using Context) =
    legalPage("Terms of Service", "terms")(
      p(
        "These terms apply to everyone who uses ",
        strong(siteName),
        ", by playing as a guest or with an account. If you do not agree with them, please do not use the site."
      ),
      h2("The service"),
      p(
        siteName,
        " is a free Go server. It is provided as it is, without any warranty: games, ratings and accounts ",
        "can be lost, and the site can be unavailable. The software is free and open source under the ",
        "GNU Affero General Public License; its ",
        a(href := sourceUrl, targetBlank)("source code"),
        " is available to everyone."
      ),
      h2("Accounts"),
      ul(
        li("One account per person. If you need another one, for example for a bot, ask us first."),
        li("Keep your password to yourself. You are responsible for what is done with your account."),
        li("Usernames must not be offensive or impersonate someone else.")
      ),
      h2("Fair play"),
      ul(
        li(
          "Play your own games: do not get help from a Go engine (AI), a book, a game database or another ",
          "person during a game against a human."
        ),
        li("Do not manipulate ratings, for example by losing on purpose or playing against your own accounts."),
        li("Playing against the computer opponent of this site is of course fine.")
      ),
      h2("Behaviour"),
      p(
        "Be respectful in games, chat and messages. Harassment, hate speech, threats, spam and sharing other ",
        "people's private information are not allowed."
      ),
      h2("Moderation"),
      p(
        "We can remove content, mark or restrict accounts, and close accounts that break these terms. ",
        "If you think a decision was wrong, ",
        a(href := routes.Main.contact)("contact us"),
        "."
      ),
      h2("Your content"),
      p(
        "Games are public: anyone can watch them, and download them as SGF. What you write in your profile, ",
        "in chat and in messages stays yours, but you allow us to store and display it to provide the service."
      ),
      h2("Changes"),
      p(
        "We may update these terms. The current version is always on this page. If you keep using the site ",
        "after a change, you accept the new terms."
      ),
      p(
        "See also the ",
        a(href := routes.Main.privacy)("Privacy policy"),
        "."
      )
    )

  def privacy(using Context) =
    legalPage("Privacy policy", "privacy")(
      p(
        "This page explains what ",
        strong(siteName),
        " stores about you, why, and how to remove it. The site shows no ads and uses no third-party ",
        "tracking or analytics."
      ),
      h2("What we store"),
      ul(
        li(
          strong("Your account"),
          ": username, email address, and your password, stored as a salted hash (never in plain text)."
        ),
        li(
          strong("Your profile and preferences"),
          ": what you write in your profile, your settings, and the players you follow or block."
        ),
        li(
          strong("Games"),
          ": the moves, times and results of your games, and the ratings they give. Games are public."
        ),
        li(strong("Chat and messages"), ": what you write in game chat and in private messages."),
        li(
          strong("Security records"),
          ": the IP addresses and browsers your account signs in from, with dates, to protect accounts ",
          "and fight abuse such as cheating with multiple accounts."
        )
      ),
      h2("Cookies"),
      p("The site only sets the cookies it needs to work:"),
      ul(
        li("a session cookie, which keeps you signed in;"),
        li("a game cookie, which lets you keep playing your games as a guest;"),
        li("a few preference cookies, such as the theme, board and stones you chose.")
      ),
      h2("Email"),
      p(
        "Your email address is used to confirm your account, to reset your password, and for the ",
        "notifications you turn on (such as correspondence game updates). It is never shown to other players."
      ),
      h2("Who can see what"),
      p(
        "Your username, profile, ratings and games are public. Your email address, security records and ",
        "private messages are not: only you, and the administrators of this server, can see them."
      ),
      h2("Your data, your choice"),
      ul(
        li(a(href := routes.Account.data)("Download your personal data"), "."),
        li(
          a(href := routes.Account.close)("Close your account"),
          ": it disappears from the site, and you can reopen it later."
        ),
        li(
          a(href := routes.Account.delete)("Delete your account"),
          ": your account and personal data are removed for good."
        )
      ),
      p(
        "Questions about your data? ",
        a(href := routes.Main.contact)("Contact us"),
        "."
      )
    )

