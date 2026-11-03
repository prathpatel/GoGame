import com.typesafe.sbt.packager.Keys.{ bashScriptExtraDefines, scriptClasspath }
import play.sbt.routes.RoutesKeys

import BuildSettings.*
import Dependencies.*

// sbt 2.0 lints "unused" keys on load. native-packager defines Debian/Rpm/Linux/Universal
// packaging keys that lila legitimately doesn't use (we only run `stage`), and RoutesCompiler
// defines playGenerateReverseRouter (we only read it in Compile scope). Silence that lint noise
// rather than wiring up packaging we don't ship.
Global / lintUnusedKeysOnLoad := false

lazy val root = Project("lila", file("."))
  .enablePlugins(JavaServerAppPackaging, RoutesCompiler)
  .dependsOn(api)
  .aggregate(api)
  .settings(buildSettings)
  .settings(
    // These configure the root "lila" app specifically (flat app/ + conf/ layout, native-packager
    // output). Under sbt 2.0 bare top-level settings propagate to every aggregated module, which
    // breaks (modules lack JavaServerAppPackaging; the `target` override clashes with sbt 2.0 task
    // caching). Scope them to the root project where they belong.
    scriptClasspath := Seq("*"),
    Compile / mainClass := Some("lila.app.Lila"),
    // Adds the Play application directory to the command line args passed to Play
    bashScriptExtraDefines += "addJava \"-Duser.dir=$(realpath \"$(cd \"${app_home}/..\"; pwd -P)\"  $(is_cygwin && echo \"fix\"))\"\n",
    Universal / sourceDirectory := baseDirectory.value / "dist",
    Compile / resourceDirectory := baseDirectory.value / "conf",
    Compile / RoutesKeys.routes / sources ++= {
      val dirs = (Compile / unmanagedResourceDirectories).value
      (dirs * "routes").get() ++ (dirs * "*.routes").get()
    },
    Compile / RoutesKeys.generateReverseRouter := false,
    Compile / RoutesKeys.generateForwardRouter := true,
    Compile / sourceDirectory := baseDirectory.value / "app",
    Compile / scalaSource := baseDirectory.value / "app",
    // Keep the native-packager stage output at target/universal/stage — lila CI tars that exact
    // path (.github/workflows/server.yml). Scope this to Universal/target rather than overriding the
    // whole project `target`: stagingDirectory = Universal/target / "stage", and the default
    // Universal/target = <project target> / "universal". Overriding only Universal/target leaves the
    // project target at sbt 2.0's default, so crossTarget stays target/out/jvm/scala-3.8.4/lila and
    // zinc's inc_compile_3.zip stays inside the cache root — no "Cannot cache" warning.
    Universal / target := baseDirectory.value / "target" / "universal"
  )

organization := "org.lichess"
Compile / run / fork := true
javaOptions ++= Seq("-Xms64m", "-Xmx512m", "-Dlogger.file=conf/logger.dev.xml")
javaOptions ++= {
  if (sys.props("java.specification.version").toInt > 21)
    Seq("--enable-native-access=ALL-UNNAMED")
  else
    Seq.empty
}
ThisBuild / scalacOptions ++= Seq("-unchecked", "-deprecation")
ThisBuild / semanticdbEnabled := true
ThisBuild / usePipelining := false
// the following settings come from the PlayScala plugin, which I removed
// shellPrompt := PlayCommands.playPrompt
ivyLoggingLevel := UpdateLogging.DownloadOnly

// format: off
libraryDependencies ++= pekko.bundle ++ playWs.bundle ++ macwire.bundle ++ scalalib.bundle ++ chess.bundle ++ Seq(
  play.json, play.logback, compression, hasher,
  reactivemongo.driver, /* reactivemongo.kamon, */ maxmind, scalatags,
  kamon.core, kamon.influxdb, kamon.metrics,
  scaffeine, caffeine, lettuce, uaparser, nettyTransport, catsMtl
) ++ tests.bundle

// influences the compilation order
// matches https://github.com/ornicar/lila-dep-graphs
lazy val modules = Seq(
  core, coreI18n, goban, common, ui, mon, markdown, db, room, memo, rating, user, game, pref, chat, playban, mailer, oauth, security, shutup, irc, report, relation, notifyModule, msg, history, bookmark, socket, i18n, pool, lobby, setup, challenge, round, web
)

lazy val moduleRefs = modules map projectToRef
lazy val moduleCPDeps = moduleRefs map { sbt.ClasspathDependency(_, None) }

lazy val core = module("core",
  Seq(goban),
  Seq(catsMtl, scalatags, galimatias) ++ scalalib.bundle ++ reactivemongo.bundle ++ tests.bundle
)

lazy val goban = module("goban",
  Seq(),
  tests.bundle
)

lazy val coreI18n = module("coreI18n",
  Seq(),
  Seq(scalatags) ++ scalalib.bundle
)

lazy val mon = module("mon",
  Seq(core),
  Seq(kamon.core, kamon.influxdb)
)

lazy val common = module("common",
  Seq(core),
  Seq(kamon.core, scaffeine, apacheText, chess.playJson)
)

lazy val markdown = module("markdown",
  Seq(core),
  flexmark.bundle
)

lazy val db = module("db",
  Seq(common),
  Seq(hasher) ++ macwire.bundle
)

lazy val memo = module("memo",
  Seq(db, mon, markdown),
  Seq(scaffeine, probabilisticDataStructures) ++ playWs.bundle
)

lazy val i18n = module("i18n",
  Seq(common, coreI18n),
  tests.bundle
).settings(
  Compile / resourceGenerators += Def.task {
    I18n.serialize(
      sourceDir = new File("translation/source"),
      destDir = new File("translation/dest"),
      dbs = "activity app appeal arena broadcast challenge class coach contact coordinates dgt emails faq features insight keyboardMove lag learn localAnalysis nvui oauthScope onboarding patron perfStat practice preferences puzzle puzzleTheme recap search settings site streamer storm study swiss team timeago tfa tourname ublog variant video voiceCommands msg report".split(' ').toList,
      outputDir = (Compile / resourceManaged).value
    )
  }.taskValue
)

lazy val rating = module("rating",
  Seq(db, ui, mon),
  tests.bundle ++ Seq(apacheMath)
).dependsOn(common % "test->test")

lazy val history = module("history",
  Seq(rating, memo),
  Seq()
)

lazy val chat = module("chat",
  Seq(memo, ui),
  tests.bundle
)

lazy val room = module("room",
  Seq(common),
  Seq(lettuce)
)

lazy val user = module("user",
  Seq(rating, memo),
  Seq(hasher) ++ tests.bundle ++ playWs.bundle
)

lazy val game = module("game",
  Seq(rating, memo),
  Seq(compression) ++ tests.bundle ++ Seq(scalacheck, munitCheck, chess.testKit)
)

lazy val round = module("round",
  Seq(room, game, user, playban, pref, chat),
  Seq(hasher, kamon.core, lettuce) ++ tests.bundle
)

lazy val pool = module("pool",
  Seq(rating),
  Seq()
)

lazy val lobby = module("lobby",
  Seq(memo, rating),
  Seq(lettuce) ++ tests.bundle
)

lazy val setup = module("setup",
  Seq(lobby),
  Seq()
)

lazy val oauth = module("oauth",
  Seq(memo, ui),
  Seq()
)

lazy val security = module("security",
  Seq(oauth, user, mailer),
  Seq(maxmind, hasher, uaparser) ++ tests.bundle
)

lazy val shutup = module("shutup",
  Seq(db, mon),
  tests.bundle
)

lazy val challenge = module("challenge",
  Seq(game, room, oauth),
  Seq(lettuce, catsMtl) ++ tests.bundle
)

lazy val playban = module("playban",
  Seq(memo),
  tests.bundle
)

lazy val mailer = module("mailer",
  Seq(memo, ui),
  Seq(hasher, play.mailer)
)

lazy val relation = module("relation",
  Seq(memo, ui),
  Seq()
)

lazy val pref = module("pref",
  Seq(memo, ui),
  Seq()
)

lazy val msg = module("msg",
  Seq(coreI18n, memo),
  Seq()
)

lazy val bookmark = module("bookmark",
  Seq(db),
  Seq()
)

lazy val report = module("report",
  Seq(ui, memo),
  Seq()
)

lazy val notifyModule = module("notify",
  Seq(memo, ui),
  Seq()
)

lazy val socket = module("socket",
  Seq(memo),
  Seq(lettuce)
)

lazy val ui = module("ui",
  Seq(core, coreI18n),
  Seq()
).enablePlugins(RoutesCompiler).settings(
  Compile / RoutesKeys.generateForwardRouter := false,
  Compile / RoutesKeys.routes / sources ++= {
    val dirs = baseDirectory.value / ".." / ".." / "conf"
    (dirs * "routes").get() ++ (dirs * "*.routes").get()
  }
)

lazy val irc = module("irc",
  Seq(common, mon),
  playWs.bundle
)

lazy val web = module("web",
  Seq(ui, memo),
  playWs.bundle ++ tests.bundle ++ Seq(
    play.logback, play.server, play.netty,
    kamon.prometheus
  )
)

lazy val api = module("api",
  moduleCPDeps,
  Seq(play.api, play.json, hasher, kamon.core, kamon.influxdb, lettuce) ++ tests.bundle ++ flexmark.bundle
).settings(
  Runtime / aggregate := false,
  Test / aggregate := true  // Test <: Runtime
).aggregate(moduleRefs*)
