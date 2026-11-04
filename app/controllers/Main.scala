package controllers
import play.api.libs.json.*
import play.api.mvc.*
import scalalib.model.Language

import lila.app.{ *, given }
import lila.common.Json.given
import lila.core.id.{ GameFullId, ImageId }
import lila.ui.MarkdownRealm

final class Main(env: Env, assetsC: ExternalAssets) extends LilaController(env):

  def toggleBlindMode = OpenBody:
    bindForm(lila.web.WebForms.blind)(
      _ => BadRequest,
      (enable, redirect) =>
        Redirect(redirect).withCookies:
          lila.web.WebConfig.blindCookie.make(env.security.lilaCookie)(enable != "0")
    )

  def handlerNotFound(msg: Option[String])(using RequestHeader) =
    makeContext.flatMap:
      keyPages.notFound(msg)(using _)

  def captchaCheck(id: GameId) = Anon:
    env.game.captcha.validate(id, ~get("solution")).map { valid =>
      Ok(if valid then 1 else 0)
    }

  def lag = Open:
    Ok.page(views.site.ui.lag)

  def playerCount = Open:
    NoCrawlers(env.web.playerCounter.website.hit())
    NoContent

  def jslog(id: GameFullId) = Open:
    env.round.selfReport(
      userId = ctx.userId,
      ip = ctx.ip,
      fullId = id,
      name = get("n") | "?"
    )
    NoContent

  val robots = Anon:
    Ok:
      if env.net.crawlable && req.domain == env.net.domain.value && env.mode.isProd
      then env.web.static.robotsTxt
      else "User-agent: *\nDisallow: /"
    .publicCache(1.hour)

  val manifest = Anon:
    Ok(env.web.static.manifest).publicCache(1.hour)

  def games = Open:
    pageHit
    for
      stored <- env.game.gameRepo.nowPlaying(48)
      // the latest moves are in memory before they reach the database
      live <- env.round.proxyRepo.gamesIfPresentOrFetch(stored.map(_.id))
      page <- Ok.page(views.game.nowPlaying(live))
    yield page

  def terms = Open:
    pageHit
    Ok.page(views.site.page.terms)

  def privacy = Open:
    pageHit
    Ok.page(views.site.page.privacy)

  def contact = Open:
    pageHit
    Ok.page(views.site.page.contact)

  def about = Open:
    pageHit
    Ok.page(views.site.page.about)

  def source = Anon:
    Redirect(lila.web.ui.sourceUrl)

  def faq = Open:
    pageHit
    Ok.page(views.site.page.faq)

  def temporarilyDisabled(@annotation.nowarn path: String) = Open:
    NotImplemented.page(views.site.message.temporarilyDisabled)

  def helpPath(path: String) = Open:
    path match
      case "keyboard-move" => Ok.snip(lila.web.ui.help.keyboardMove)
      case "voice/move" => Ok.snip(lila.web.ui.help.voiceMove)
      case _ => notFound

  def movedPermanently(to: String) = Anon:
    MovedPermanently(to)

  def prometheusMetrics(key: String) = Anon:
    if key == env.web.config.prometheusKey
    then Ok(lila.web.PrometheusReporter.latestScrapeData()).publicCache(59.seconds)
    else NotFound("Invalid prometheus key")

  def legacyQaQuestion(id: Int, @annotation.nowarn slug: String) = Anon:
    MovedPermanently:
      env.web.static.legacyQaQuestion(id)

  def devAsset(@annotation.nowarn v: String, path: String, file: String) = assetsC.at(path, file)

  def uploadImage(realm: MarkdownRealm) = AuthBody(lila.web.HashedMultiPart(parse)) { ctx ?=> me ?=>
    lila.core.security
      .canUploadImages(realm.key)
      .so:
        limit.imageUpload(rateLimited):
          ctx.body.body.file("image") match
            case None => JsonBadRequest("Image content only")
            case Some(image) =>
              val meta = lila.memo.PicfitApi.form.upload.bindFromRequest().value
              (for
                image <- env.memo.picfitApi.uploadFile(image, me, none, meta)
                maxWidth = realm.imageDesignWidth
                url = meta match
                  case Some(info) if info.dim.width > maxWidth =>
                    env.memo.picfitUrl.resize(image.id, Left(maxWidth)).some
                  case _ => env.memo.picfitUrl.raw(image.id).some
              yield Ok(Json.obj("imageUrl" -> url))).recover:
                case lila.core.lilaism.LilaInvalid(msg) => UnprocessableEntity(jsonError(msg))
  }

  def imageUrl(realm: MarkdownRealm, id: ImageId, width: Int) = Auth { _ ?=> me ?=>
    if width < 1 then JsonBadRequest("Invalid width")
    else
      limit.preview(me.userId, rateLimited):
        Ok(Json.obj("imageUrl" -> env.memo.picfitUrl.resize(id, Left(width.min(realm.imageDesignWidth)))))
  }

  def markdownPreview(realm: MarkdownRealm) = AuthBody(parse.tolerantText) { ctx ?=> me ?=>
    val renderKey = s"${realm.key}:${me.userId}"
    val options = lila.memo.MarkdownOptions(autoLink = true, list = true, strikeThrough = true, blockQuote = true, code = true)

    env.memo.markdown
      .toHtml(renderKey, Markdown(ctx.body.body), options)
      .map(_.frag)
      .map(Ok.snip(_))
  }
