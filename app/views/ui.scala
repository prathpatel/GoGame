package views

import lila.app.UiEnv.{ *, given }
export lila.web.ui.bits

val captcha = lila.web.ui.CaptchaUi(helpers)

val chat = lila.chat.ChatUi

val setup = lila.setup.ui.SetupUi(helpers)

val atomUi = lila.ui.AtomUi(helpers.routeUrl)

val relation = lila.relation.ui.RelationUi(helpers)

val auth = lila.web.ui.AuthUi(helpers)

object oAuth:
  val token = lila.oauth.ui.TokenUi(helpers)(account.ui.AccountPage, env.mode)
  val authorize = lila.oauth.ui.AuthorizeUi(helpers)(lightUserFallback, auth.customLogo)

object account:
  val ui = lila.pref.ui.AccountUi(helpers)
  val pages = lila.pref.ui.AccountPages(helpers, ui, flagApi)
  val pref = lila.pref.ui.AccountPref(helpers, prefHelper, ui)
  val twoFactor = lila.pref.ui.TwoFactorUi(helpers, ui)(netConfig.domain)
  val security = lila.security.ui.AccountSecurity(helpers)(ui.AccountPage)

val challenge = lila.challenge.ui.ChallengeUi(helpers)
