package lila.pref
package ui

import play.api.data.Form

import lila.ui.*
import lila.ui.ScalatagsTemplate.{ *, given }

final class AccountPref(helpers: Helpers, helper: PrefHelper, bits: AccountUi):
  import helpers.{ *, given }
  import helper.*
  import bits.*
  import trans.preferences as trp

  private def categFieldset(categ: lila.pref.PrefCateg, active: lila.pref.PrefCateg) =
    div(cls := List("none" -> (categ != active)))

  // chess-only settings: still posted with the form (every field is required), never shown
  private val hiddenChess = style := "display:none"

  // the Go board shows coordinates around the board, or not at all
  private def goCoordinateChoices(using Translate) =
    List((Pref.Coords.NONE, trans.site.no.txt()), (Pref.Coords.INSIDE, trans.site.yes.txt()))

  private def setting(name: Frag, body: Frag, settingId: String) =
    st.section(a(href := "#" + settingId)(h2(id := settingId)(name)), body)

  def apply(u: User, form: play.api.data.Form[?], categ: lila.pref.PrefCateg)(using Context) =
    AccountPage(
      s"${bits.categName(categ)} - ${u.username} - ${trans.preferences.preferences.txt()}",
      categ.slug
    ):
      val booleanChoices = translatedBooleanIntChoices
      div(cls := "box box-pad")(
        h1(cls := "box__top")(bits.categName(categ)),
        postForm(cls := "autosubmit", action := routes.Pref.formApply)(
          categFieldset(PrefCateg.Display, categ)(
            setting(
              trp.pieceAnimation(),
              radios(form("display.animation"), translatedAnimationChoices),
              "pieceAnimation"
            )(hiddenChess),
            setting(
              trp.materialDifference(),
              radios(form("display.captured"), booleanChoices),
              "materialDifference"
            )(hiddenChess),
            setting(
              trp.boardHighlights(),
              radios(form("display.highlight"), booleanChoices),
              "boardHighlights"
            )(hiddenChess),
            setting(
              trp.pieceDestinations(),
              radios(form("display.destination"), booleanChoices),
              "pieceDestinations"
            )(hiddenChess),
            setting(
              "Board coordinates",
              radios(form("display.coords"), goCoordinateChoices),
              "boardCoordinates"
            ),
            setting(
              trp.moveListWhilePlaying(),
              radios(form("display.replay"), translatedMoveListWhilePlayingChoices),
              "moveListWhilePlaying"
            )(hiddenChess),
            setting(
              trp.pgnPieceNotation(),
              radios(form("display.pieceNotation"), translatedPieceNotationChoices),
              "pgnPieceNotation"
            )(hiddenChess),
            setting(
              trp.zenMode(),
              radios(form("display.zen"), translatedZenChoices),
              "zenMode"
            ),
            setting(
              trp.displayBoardResizeHandle(),
              radios(form("display.resizeHandle"), translatedBoardResizeHandleChoices),
              "displayBoardResizeHandle"
            )(hiddenChess),
            setting(
              trp.showPlayerRatings(),
              frag(
                radios(form("ratings"), translatedRatingsChoices),
                div(cls := "help text shy", dataIcon := Icon.InfoCircle)(
                  "Hides all ratings from the site, to help you focus on the game. ",
                  "Rated games still change your rating."
                )
              ),
              "showRatings"
            ),
            setting(
              trp.showFlairs(),
              radios(form("flairs"), translatedBooleanChoices),
              "showFlairs"
            )
          ),
          categFieldset(PrefCateg.ChessClock, categ)(
            setting(
              trp.tenthsOfSeconds(),
              radios(form("clock.tenths"), translatedClockTenthsChoices),
              "tenthsOfSeconds"
            ),
            setting(
              trp.horizontalGreenProgressBars(),
              radios(form("clock.bar"), booleanChoices),
              "horizontalGreenProgressBars"
            )(hiddenChess),
            setting(
              trp.soundWhenTimeGetsCritical(),
              radios(form("clock.sound"), booleanChoices),
              "soundWhenTimeGetsCritical"
            )(hiddenChess),
            setting(
              trp.giveMoreTime(),
              radios(form("clock.moretime"), translatedMoretimeChoices),
              "giveMoreTime"
            )(hiddenChess),
            setting(
              trp.showClockOnTheLeft(),
              radios(form("clock.swapClock"), booleanChoices),
              "swapClock"
            )(hiddenChess)
          ),
          categFieldset(PrefCateg.GameBehavior, categ)(
            setting(
              trp.howDoYouMovePieces(),
              radios(form("behavior.moveEvent"), translatedMoveEventChoices),
              "howDoYouMovePieces"
            )(hiddenChess),
            setting(
              trp.premovesPlayingDuringOpponentTurn(),
              radios(form("behavior.premove"), booleanChoices),
              "premovesPlayingDuringOpponentTurn"
            )(hiddenChess),
            setting(
              trp.takebacksWithOpponentApproval(),
              radios(form("behavior.takeback"), translatedTakebackChoices),
              "takebacksWithOpponentApproval"
            ),
            setting(
              trp.promoteToQueenAutomatically(),
              frag(
                radios(form("behavior.autoQueen"), translatedAutoQueenChoices),
                div(cls := "help text shy", dataIcon := Icon.InfoCircle)(
                  trp.explainPromoteToQueenAutomatically()
                )
              ),
              "promoteToQueenAutomatically"
            )(hiddenChess),
            setting(
              trp.claimDrawOnThreefoldRepetitionAutomatically(),
              radios(form("behavior.autoThreefold"), translatedAutoThreefoldChoices),
              "claimDrawOnThreefoldRepetitionAutomatically"
            )(hiddenChess),
            setting(
              trp.moveConfirmation(),
              frag(
                bitCheckboxes(form("behavior.submitMove"), submitMoveChoices),
                div(cls := "help text shy", dataIcon := Icon.InfoCircle)(
                  trp.multipleChoices(),
                  trp.explainCanThenBeTemporarilyDisabled()
                )
              ),
              "moveConfirmation"
            )(hiddenChess),
            setting(
              trp.confirmResignationAndDrawOffers(),
              radios(form("behavior.confirmResign"), confirmResignChoices),
              "confirmResignationAndDrawOffers"
            )(hiddenChess),
            setting(
              trp.castleByMovingTheKingTwoSquaresOrOntoTheRook(),
              radios(form("behavior.rookCastle"), translatedRookCastleChoices),
              "castleByMovingTheKingTwoSquaresOrOntoTheRook"
            )(hiddenChess),
            setting(
              trp.inputMovesWithTheKeyboard(),
              radios(form("behavior.keyboardMove"), booleanChoices),
              "inputMovesWithTheKeyboard"
            )(hiddenChess),
            setting(
              trp.inputMovesWithVoice(),
              radios(form("behavior.voice"), booleanChoices),
              "inputMovesWithVoice"
            )(hiddenChess),
            setting(
              trp.snapArrowsToValidMoves(),
              radios(form("behavior.arrowSnap"), booleanChoices),
              "snapArrowsToValidMoves"
            )(hiddenChess)(cls := "arrow-snap"),
            setting(
              trp.sayGgWpAfterLosingOrDrawing(),
              radios(form("behavior.sayGG"), translatedSayGGChoices),
              "sayGG"
            ),
            setting(
              trp.scrollOnTheBoardToReplayMoves(),
              radios(form("behavior.scrollMoves"), booleanChoices),
              "scrollOnTheBoardToReplayMoves"
            )(hiddenChess)
          ),
          categFieldset(PrefCateg.Privacy, categ)(
            setting(
              trans.site.letOtherPlayersFollowYou(),
              radios(form("follow"), booleanChoices),
              "letOtherPlayersFollowYou"
            ),
            setting(
              trans.site.letOtherPlayersChallengeYou(),
              radios(form("challenge"), translatedChallengeChoices),
              "letOtherPlayersChallengeYou"
            ),
            setting(
              trans.site.letOtherPlayersMessageYou(),
              radios(form("message"), translatedMessageChoices),
              "letOtherPlayersMessageYou"
            ),
            setting(
              trans.site.letOtherPlayersInviteYouToStudy(),
              radios(form("studyInvite"), translatedStudyInviteChoices),
              "letOtherPlayersInviteYouToStudy"
            )(hiddenChess),
            setting(
              trans.site.shareYourInsightsData(),
              radios(form("insightShare"), translatedInsightShareChoices),
              "shareYourInsightsData"
            )(hiddenChess)
          ),
          p(cls := "saved text none", dataIcon := Icon.Checkmark)(trp.yourPreferencesHaveBeenSaved())
        )
      )

  object notification:

    def apply(form: Form[?])(using Context) =
      AccountPage(
        s"${trans.preferences.notifications.txt()} - ${trp.preferences.txt()}",
        "notification"
      ):
        div(cls := "box box-pad")(
          h1(cls := "box__top")(trans.preferences.notifications()),
          postForm(cls := "autosubmit", action := routes.Pref.notifyFormApply)(
            div(
              table(cls := "allows")(
                thead(
                  tr(
                    th,
                    th("On the site", iconTag(Icon.BellOutline)),
                    th("On your device, when you're away", iconTag(Icon.PhoneMobile))
                  )
                ),
                tbody(
                  List(
                    trp.notifyDirectMessage() -> "privateMessage",
                    trp.notifyChallenge() -> "challenge",
                    trp.notifyGameEvent() -> "gameEvent"
                  ).map(makeRow(form)) ::: List(
                    "streamStart",
                    "mention",
                    "invitedStudy",
                    "tournamentSoon",
                    "broadcastRound",
                    "teamUpdate"
                  ).map(key => makeRow(form)(frag(key), key)(hiddenChess))
                )
              ),
              div(
                id := "correspondence-email-notif"
              )( // id is set to allow direct unsubcribe link in correspondence emails
                bits.setting(
                  trp.correspondenceEmailNotification(),
                  radios(form("correspondenceEmail"), translatedBooleanChoices)
                )
              ),
              bits.setting(
                trp.bellNotificationSound(),
                radios(form("notification.playBellSound"), translatedBooleanIntChoices)
              )
            ),
            p(cls := "saved text none", dataIcon := Icon.Checkmark)(trp.yourPreferencesHaveBeenSaved())
          )
        )

    private def makeRow(form: play.api.data.Form[?])(transFrag: Frag, filterName: String) =
      tr(
        td(transFrag),
        Seq("bell", "push").map: allow =>
          val name = s"$filterName.$allow"
          val checked = form.data(name).contains("true")
          td(
            if !hiddenFields(s"$filterName.$allow") then
              div(cls := "toggle", form3.nativeCheckbox(name, name, checked))
            else if !checked then div(iconTag(Icon.X))
            else
              div(
                cls := "always-on",
                form3.hidden(name, "true"),
                filterName match
                  case "challenge" => iconTag(Icon.Swords)
                  case "privateMessage" => iconTag(Icon.BellOutline)
                  case _ => emptyFrag
              )
          )
      )

    private val hiddenFields = Set(
      "privateMessage.bell",
      "tournamentSoon.bell",
      "gameEvent.bell",
      "challenge.bell"
    )

  def network(cfRouting: Boolean)(using ctx: Context) =
    AccountPage(trp.network.txt(), "network"):
      div(cls := "box box-pad")(
        standardFlash,
        h1(cls := "box__top")(trp.network()),
        flashMessage("quiet")(
          if cfRouting then trp.youAreCurrentlyUsingCdnRouting()
          else trp.youAreCurrentlyUsingDirectRouting(),
          br,
          trp.frequentDisconnectsAdvice()
        ),
        br,
        postForm(action := routes.Pref.networkPost):
          button(
            name := "cfRouting",
            value := !cfRouting,
            cls := "button"
          )(if cfRouting then trp.useDirectRouting() else trp.useCdnRouting())
      )
