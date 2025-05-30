import com.softwaremill.Publish.{ossPublishSettings, updateDocs}
import com.softwaremill.SbtSoftwareMillBrowserTestJS._
import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.UpdateVersionInDocs
import com.typesafe.tools.mima.core.{Problem, ProblemFilters}
import sbt.Reference.display
import sbt.internal.ProjectMatrix

// explicit import to avoid clash with gatling plugin
import sbtassembly.AssemblyPlugin.autoImport.assembly

import java.net.URL
import scala.concurrent.duration.DurationInt
import scala.sys.process.Process

val scala2_12 = "2.12.16"
val scala2_13 = "2.13.8"
val scala3 = "3.1.3"

val scala2Versions = List(scala2_12, scala2_13)
val scala2And3Versions = scala2Versions ++ List(scala3)
val codegenScalaVersions = List(scala2_12)
val examplesScalaVersions = List(scala2_13)
val documentationScalaVersion = scala2_13

lazy val clientTestServerPort = settingKey[Int]("Port to run the client interpreter test server on")
lazy val startClientTestServer = taskKey[Unit]("Start a http server used by client interpreter tests")
lazy val generateMimeByExtensionDB = taskKey[Unit]("Generate the mime by extension DB")

concurrentRestrictions in Global += Tags.limit(Tags.Test, 1)

excludeLintKeys in Global ++= Set(ideSkipProject, reStartArgs)

val CompileAndTest = "compile->compile;test->test"

def versionedScalaSourceDirectories(sourceDir: File, scalaVersion: String): List[File] =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((3, _))            => List(sourceDir / "scala-3")
    case Some((2, n)) if n >= 13 => List(sourceDir / "scala-2", sourceDir / "scala-2.13+")
    case _                       => List(sourceDir / "scala-2", sourceDir / "scala-2.13-")
  }

def versionedScalaJvmSourceDirectories(sourceDir: File, scalaVersion: String): List[File] =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((3, _)) => List(sourceDir / "scalajvm-3")
    case _            => List(sourceDir / "scalajvm-2")
  }

val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.sttp.tapir",
  Compile / unmanagedSourceDirectories ++= versionedScalaSourceDirectories((Compile / sourceDirectory).value, scalaVersion.value),
  Test / unmanagedSourceDirectories ++= versionedScalaSourceDirectories((Test / sourceDirectory).value, scalaVersion.value),
  updateDocs := Def.taskDyn {
    val files1 = UpdateVersionInDocs(sLog.value, organization.value, version.value)
    Def.task {
      (documentation.jvm(documentationScalaVersion) / mdoc).toTask("").value
      files1 ++ Seq(file("generated-doc/out"))
    }
  }.value,
  mimaPreviousArtifacts := Set.empty, // we only use MiMa for `core` for now, using enableMimaSettings
  ideSkipProject := (scalaVersion.value == scala2_12) ||
    (scalaVersion.value == scala3) ||
    thisProjectRef.value.project.contains("Native") ||
    thisProjectRef.value.project.contains("JS"),
  // slow down for CI
  Test / parallelExecution := false,
  // remove false alarms about unused implicit definitions in macros
  scalacOptions ++= Seq("-Ywarn-macros:after"),
  evictionErrorLevel := Level.Info
)

val versioningSchemeSettings = Seq(versionScheme := Some("early-semver"))

val enableMimaSettings = Seq(
  mimaPreviousArtifacts := {
    val current = version.value
    val isRcOrMilestone = current.contains("M") || current.contains("RC")
    if (!isRcOrMilestone) {
      val previous = previousStableVersion.value
      println(s"[info] Not a M or RC version, using previous version for MiMa check: $previous")
      previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet
    } else {
      println(s"[info] $current is an M or RC version, no previous version to check with MiMa")
      Set.empty
    }
  },
  mimaBinaryIssueFilters ++= Seq(
    ProblemFilters.exclude[Problem]("sttp.tapir.internal.*"),
    ProblemFilters.exclude[Problem]("sttp.tapir.generic.internal.*"),
    ProblemFilters.exclude[Problem]("sttp.tapir.typelevel.internal.*")
  )
)

val commonJvmSettings: Seq[Def.Setting[_]] = commonSettings ++ Seq(
  Compile / unmanagedSourceDirectories ++= versionedScalaJvmSourceDirectories((Compile / sourceDirectory).value, scalaVersion.value),
  Test / unmanagedSourceDirectories ++= versionedScalaJvmSourceDirectories((Test / sourceDirectory).value, scalaVersion.value),
  Test / testOptions += Tests.Argument("-oD") // js has other options which conflict with timings
)

// run JS tests inside Gecko, due to jsdom not supporting fetch and to avoid having to install node
val commonJsSettings = commonSettings ++ browserGeckoTestSettings

val commonNativeSettings = commonSettings

def dependenciesFor(version: String)(deps: (Option[(Long, Long)] => ModuleID)*): Seq[ModuleID] =
  deps.map(_.apply(CrossVersion.partialVersion(version)))

val scalaTest = Def.setting("org.scalatest" %%% "scalatest" % Versions.scalaTest)
val scalaCheck = Def.setting("org.scalacheck" %%% "scalacheck" % Versions.scalaCheck)
val scalaTestPlusScalaCheck = {
  val scalaCheckSuffix = Versions.scalaCheck.split('.').take(2).mkString("-")
  Def.setting("org.scalatestplus" %%% s"scalacheck-$scalaCheckSuffix" % Versions.scalaTestPlusScalaCheck)
}

lazy val loggerDependencies = Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.11",
  "ch.qos.logback" % "logback-core" % "1.2.11",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
)

lazy val rawAllAggregates = core.projectRefs ++
  testing.projectRefs ++
  cats.projectRefs ++
  enumeratum.projectRefs ++
  refined.projectRefs ++
  zio1.projectRefs ++
  zio.projectRefs ++
  newtype.projectRefs ++
  circeJson.projectRefs ++
  jsoniterScala.projectRefs ++
  prometheusMetrics.projectRefs ++
  opentelemetryMetrics.projectRefs ++
  datadogMetrics.projectRefs ++
  json4s.projectRefs ++
  playJson.projectRefs ++
  sprayJson.projectRefs ++
  uPickleJson.projectRefs ++
  tethysJson.projectRefs ++
  zio1Json.projectRefs ++
  zioJson.projectRefs ++
  apispecDocs.projectRefs ++
  openapiDocs.projectRefs ++
  asyncapiDocs.projectRefs ++
  swaggerUi.projectRefs ++
  swaggerUiBundle.projectRefs ++
  redoc.projectRefs ++
  redocBundle.projectRefs ++
  serverTests.projectRefs ++
  serverCore.projectRefs ++
  akkaHttpServer.projectRefs ++
  armeriaServer.projectRefs ++
  armeriaServerCats.projectRefs ++
  armeriaServerZio.projectRefs ++
  armeriaServerZio1.projectRefs ++
  http4sServer.projectRefs ++
  http4sServerZio1.projectRefs ++
  http4sServerZio.projectRefs ++
  sttpStubServer.projectRefs ++
  sttpMockServer.projectRefs ++
  finatraServer.projectRefs ++
  finatraServerCats.projectRefs ++
  playServer.projectRefs ++
  vertxServer.projectRefs ++
  vertxServerCats.projectRefs ++
  vertxServerZio.projectRefs ++
  vertxServerZio1.projectRefs ++
  nettyServer.projectRefs ++
  nettyServerCats.projectRefs ++
  zio1HttpServer.projectRefs ++
  zioHttpServer.projectRefs ++
  awsLambda.projectRefs ++
  awsLambdaTests.projectRefs ++
  awsSam.projectRefs ++
  awsTerraform.projectRefs ++
  awsExamples.projectRefs ++
  clientCore.projectRefs ++
  http4sClient.projectRefs ++
  sttpClient.projectRefs ++
  sttpClientWsZio1.projectRefs ++
  playClient.projectRefs ++
  tests.projectRefs ++
  perfTests.projectRefs ++
  examples.projectRefs ++
  examples3.projectRefs ++
  documentation.projectRefs ++
  openapiCodegenCore.projectRefs ++
  openapiCodegenSbt.projectRefs ++
  openapiCodegenCli.projectRefs ++
  clientTestServer.projectRefs ++
  derevo.projectRefs

lazy val allAggregates: Seq[ProjectReference] = {
  if (sys.env.isDefinedAt("STTP_NATIVE")) {
    println("[info] STTP_NATIVE defined, including native in the aggregate projects")
    rawAllAggregates
  } else {
    println("[info] STTP_NATIVE *not* defined, *not* including native in the aggregate projects")
    rawAllAggregates.filterNot(_.toString.contains("Native"))
  }
}

// separating testing into different Scala versions so that it's not all done at once, as it causes memory problems on CI
val testJVM_2_12 = taskKey[Unit]("Test JVM Scala 2.12 projects, without Finatra")
val testJVM_2_13 = taskKey[Unit]("Test JVM Scala 2.13 projects, without Finatra")
val testJVM_3 = taskKey[Unit]("Test JVM Scala 3 projects, without Finatra")
val testJS = taskKey[Unit]("Test JS projects")
val testNative = taskKey[Unit]("Test native projects")
val testDocs = taskKey[Unit]("Test docs projects")
val testServers = taskKey[Unit]("Test server projects")
val testClients = taskKey[Unit]("Test client projects")
val testOther = taskKey[Unit]("Test other projects")
val testFinatra = taskKey[Unit]("Test Finatra projects")

def filterProject(p: String => Boolean) =
  ScopeFilter(inProjects(allAggregates.filter(pr => p(display(pr.project))): _*))

lazy val macros = Seq(
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 11 | 12)) => List(compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.patch))
      case _                  => List()
    }
  },
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, y)) if y == 11 => Seq("-Xexperimental")
      case Some((2, y)) if y == 13 => Seq("-Ymacro-annotations")
      case _                       => Seq.empty[String]
    }
  },
  // remove false alarms about unused implicit definitions in macros
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Ywarn-macros:after")
      case _            => Seq.empty[String]
    }
  }
)

lazy val rootProject = (project in file("."))
  .settings(commonSettings)
  .settings(mimaPreviousArtifacts := Set.empty)
  .settings(
    publishArtifact := false,
    name := "tapir",
    testJVM_2_12 := (Test / test)
      .all(filterProject(p => !p.contains("JS") && !p.contains("Native") && !p.contains("finatra") && p.contains("2_12")))
      .value,
    testJVM_2_13 := (Test / test)
      .all(
        filterProject(p => !p.contains("JS") && !p.contains("Native") && !p.contains("finatra") && !p.contains("2_12") && !p.contains("3"))
      )
      .value,
    testJVM_3 := (Test / test)
      .all(filterProject(p => !p.contains("JS") && !p.contains("Native") && !p.contains("finatra") && p.contains("3")))
      .value,
    testJS := (Test / test).all(filterProject(_.contains("JS"))).value,
    testNative := (Test / test).all(filterProject(_.contains("Native"))).value,
    testDocs := (Test / test).all(filterProject(p => p.contains("Docs") || p.contains("openapi") || p.contains("asyncapi"))).value,
    testServers := (Test / test).all(filterProject(p => p.contains("Server"))).value,
    testClients := (Test / test).all(filterProject(p => p.contains("Client"))).value,
    testOther := (Test / test)
      .all(
        filterProject(p =>
          !p.contains("Server") && !p.contains("Client") && !p.contains("Docs") && !p.contains("openapi") && !p.contains("asyncapi")
        )
      )
      .value,
    testFinatra := (Test / test).all(filterProject(p => p.contains("finatra"))).value,
    ideSkipProject := false,
    generateMimeByExtensionDB := GenerateMimeByExtensionDB()
  )
  .aggregate(allAggregates: _*)

// start a test server before running tests of a client interpreter; this is required both for JS tests run inside a
// nodejs/browser environment, as well as for JVM tests where akka-http isn't available (e.g. dotty).
val clientTestServerSettings = Seq(
  Test / test := (Test / test)
    .dependsOn(clientTestServer2_13 / startClientTestServer)
    .value,
  Test / testOnly := (Test / testOnly)
    .dependsOn(clientTestServer2_13 / startClientTestServer)
    .evaluated,
  Test / testOptions += Tests.Setup(() => {
    val port = (clientTestServer2_13 / clientTestServerPort).value
    PollingUtils.waitUntilServerAvailable(new URL(s"http://localhost:$port"))
  })
)

lazy val clientTestServer = (projectMatrix in file("client/testserver"))
  .settings(commonJvmSettings)
  .settings(
    name := "testing-server",
    publish / skip := true,
    libraryDependencies ++= loggerDependencies ++ Seq(
      "org.http4s" %% "http4s-dsl" % Versions.http4s,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer,
      "org.http4s" %% "http4s-circe" % Versions.http4s
    ),
    // the test server needs to be started before running any client tests
    reStart / mainClass := Some("sttp.tapir.client.tests.HttpServer"),
    reStart / reStartArgs := Seq(s"${(Test / clientTestServerPort).value}"),
    reStart / fullClasspath := (Test / fullClasspath).value,
    clientTestServerPort := 51823,
    startClientTestServer := reStart.toTask("").value
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)

lazy val clientTestServer2_13 = clientTestServer.jvm(scala2_13)

// core

lazy val core: ProjectMatrix = (projectMatrix in file("core"))
  .settings(commonSettings)
  .settings(versioningSchemeSettings)
  .settings(
    name := "tapir-core",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.model" %%% "core" % Versions.sttpModel,
      "com.softwaremill.sttp.shared" %%% "core" % Versions.sttpShared,
      "com.softwaremill.sttp.shared" %%% "ws" % Versions.sttpShared,
      scalaTest.value % Test,
      scalaCheck.value % Test,
      scalaTestPlusScalaCheck.value % Test
    ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((3, _)) =>
          Seq("com.softwaremill.magnolia1_3" %%% "magnolia" % "1.1.4")
        case _ =>
          Seq(
            "com.softwaremill.magnolia1_2" %%% "magnolia" % "1.1.2",
            "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided
          )
      }
    },
    // Until https://youtrack.jetbrains.com/issue/SCL-18636 is fixed and IntelliJ properly imports projects with
    // generated sources, they are explicitly added to git. See also below: commented out plugin.
    Compile / unmanagedSourceDirectories += {
      (Compile / sourceDirectory).value / "boilerplate-gen"
    }
  )
  .jvmPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJvmSettings ++ enableMimaSettings
  )
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings ++ Seq(
      libraryDependencies ++= Seq(
        "org.scala-js" %%% "scalajs-dom" % "2.2.0",
        // TODO: remove once https://github.com/scalatest/scalatest/issues/2116 is fixed
        ("org.scala-js" %%% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13) % Test,
        "io.github.cquiroz" %%% "scala-java-time" % Versions.jsScalaJavaTime % Test,
        "io.github.cquiroz" %%% "scala-java-time-tzdb" % Versions.jsScalaJavaTime % Test
      )
    )
  )
  .nativePlatform(
    scalaVersions = scala2And3Versions,
    settings = {
      commonNativeSettings ++ Seq(
        libraryDependencies ++= Seq(
          "io.github.cquiroz" %%% "scala-java-time" % Versions.nativeScalaJavaTime,
          "io.github.cquiroz" %%% "scala-java-time-tzdb" % Versions.nativeScalaJavaTime % Test
        )
      )
    }
  )
//.enablePlugins(spray.boilerplate.BoilerplatePlugin)

lazy val testing: ProjectMatrix = (projectMatrix in file("testing"))
  .settings(commonSettings)
  .settings(
    name := "tapir-testing",
    libraryDependencies ++= Seq(scalaTest.value % Test) ++ loggerDependencies
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(scalaVersions = scala2And3Versions, settings = commonJsSettings)
  .nativePlatform(scalaVersions = scala2And3Versions, settings = commonNativeSettings)
  .dependsOn(core)

lazy val tests: ProjectMatrix = (projectMatrix in file("tests"))
  .settings(commonSettings)
  .settings(
    name := "tapir-tests",
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-generic" % Versions.circe,
      "com.softwaremill.common" %%% "tagging" % "2.3.3",
      scalaTest.value,
      "org.typelevel" %%% "cats-effect" % Versions.catsEffect
    ) ++ loggerDependencies
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings
  )
  .dependsOn(core, circeJson, cats)

val akkaHttpVanilla = taskKey[Unit]("akka-http-vanilla")
val akkaHttpTapir = taskKey[Unit]("akka-http-tapir")
val akkaHttpVanillaMulti = taskKey[Unit]("akka-http-vanilla-multi")
val akkaHttpTapirMulti = taskKey[Unit]("akka-http-tapir-multi")
val http4sVanilla = taskKey[Unit]("http4s-vanilla")
val http4sTapir = taskKey[Unit]("http4s-tapir")
val http4sVanillaMulti = taskKey[Unit]("http4s-vanilla-multi")
val http4sTapirMulti = taskKey[Unit]("http4s-tapir-multi")
def genPerfTestTask(servName: String, simName: String) = Def.taskDyn {
  Def.task {
    (Compile / runMain).toTask(s" sttp.tapir.perf.${servName}Server").value
    (Gatling / testOnly).toTask(s" sttp.tapir.perf.${simName}Simulation").value
  }
}

lazy val perfTests: ProjectMatrix = (projectMatrix in file("perf-tests"))
  .enablePlugins(GatlingPlugin)
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-perf-tests",
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.8.2" % "test",
      "io.gatling" % "gatling-test-framework" % "3.8.2" % "test",
      "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp,
      "com.typesafe.akka" %% "akka-stream" % Versions.akkaStreams,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer,
      "org.http4s" %% "http4s-server" % Versions.http4s,
      "org.http4s" %% "http4s-core" % Versions.http4s,
      "org.http4s" %% "http4s-dsl" % Versions.http4s,
      "org.typelevel" %%% "cats-effect" % Versions.catsEffect
    ) ++ loggerDependencies,
    publishArtifact := false
  )
  .settings(Gatling / scalaSource := sourceDirectory.value / "test" / "scala")
  .settings(
    fork := true,
    connectInput := true
  )
  .settings(akkaHttpVanilla := { (genPerfTestTask("akka.Vanilla", "OneRoute")).value })
  .settings(akkaHttpTapir := { (genPerfTestTask("akka.Tapir", "OneRoute")).value })
  .settings(akkaHttpVanillaMulti := { (genPerfTestTask("akka.VanillaMulti", "MultiRoute")).value })
  .settings(akkaHttpTapirMulti := { (genPerfTestTask("akka.TapirMulti", "MultiRoute")).value })
  .settings(http4sVanilla := { (genPerfTestTask("http4s.Vanilla", "OneRoute")).value })
  .settings(http4sTapir := { (genPerfTestTask("http4s.Tapir", "OneRoute")).value })
  .settings(http4sVanillaMulti := { (genPerfTestTask("http4s.VanillaMulti", "MultiRoute")).value })
  .settings(http4sTapirMulti := { (genPerfTestTask("http4s.TapirMulti", "MultiRoute")).value })
  .jvmPlatform(scalaVersions = examplesScalaVersions)
  .dependsOn(core, akkaHttpServer, http4sServer)

// integrations

lazy val cats: ProjectMatrix = (projectMatrix in file("integrations/cats"))
  .settings(commonSettings)
  .settings(
    name := "tapir-cats",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % "2.8.0",
      "org.typelevel" %%% "cats-effect" % Versions.catsEffect,
      scalaTest.value % Test,
      scalaCheck.value % Test,
      scalaTestPlusScalaCheck.value % Test,
      "org.typelevel" %%% "discipline-scalatest" % "2.2.0" % Test,
      "org.typelevel" %%% "cats-laws" % "2.8.0" % Test
    )
  )
  .jvmPlatform(
    scalaVersions = scala2And3Versions,
    // tests for cats3 are disable until https://github.com/lampepfl/dotty/issues/12849 is fixed
    settings = Seq(
      Test / skip := scalaVersion.value == scala3,
      Test / test := {
        if (scalaVersion.value == scala3) () else (Test / test).value
      }
    )
  )
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings ++ Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time" % Versions.jsScalaJavaTime % Test
      )
    )
  )
  .dependsOn(core)

lazy val enumeratum: ProjectMatrix = (projectMatrix in file("integrations/enumeratum"))
  .settings(commonSettings)
  .settings(
    name := "tapir-enumeratum",
    libraryDependencies ++= Seq(
      "com.beachape" %%% "enumeratum" % Versions.enumeratum,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .jsPlatform(
    scalaVersions = scala2Versions,
    settings = commonJsSettings ++ Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time" % Versions.jsScalaJavaTime % Test
      )
    )
  )
  .dependsOn(core)

lazy val refined: ProjectMatrix = (projectMatrix in file("integrations/refined"))
  .settings(commonSettings)
  .settings(
    name := "tapir-refined",
    libraryDependencies ++= Seq(
      "eu.timepit" %%% "refined" % Versions.refined,
      scalaTest.value % Test,
      "io.circe" %%% "circe-refined" % Versions.circe % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings ++ Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time" % Versions.jsScalaJavaTime % Test
      )
    )
  )
  .dependsOn(core, circeJson % Test)

lazy val zio1: ProjectMatrix = (projectMatrix in file("integrations/zio1"))
  .settings(commonSettings)
  .settings(
    name := "tapir-zio1",
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Versions.zio1,
      "dev.zio" %% "zio-streams" % Versions.zio1,
      "dev.zio" %% "zio-test" % Versions.zio1 % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio1 % Test,
      "com.softwaremill.sttp.shared" %% "zio1" % Versions.sttpShared
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(core, serverCore % Test)

lazy val zio: ProjectMatrix = (projectMatrix in file("integrations/zio"))
  .settings(commonSettings)
  .settings(
    name := "tapir-zio",
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-streams" % Versions.zio,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test,
      "com.softwaremill.sttp.shared" %% "zio" % Versions.sttpShared
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(core, serverCore % Test)

lazy val derevo: ProjectMatrix = (projectMatrix in file("integrations/derevo"))
  .settings(commonSettings)
  .settings(macros)
  .settings(
    name := "tapir-derevo",
    libraryDependencies ++= Seq(
      "tf.tofu" %% "derevo-core" % Versions.derevo,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(core, newtype)

lazy val newtype: ProjectMatrix = (projectMatrix in file("integrations/newtype"))
  .settings(commonSettings)
  .settings(macros)
  .settings(
    name := "tapir-newtype",
    libraryDependencies ++= Seq(
      "io.estatico" %%% "newtype" % Versions.newtype,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .jsPlatform(
    scalaVersions = scala2Versions,
    settings = commonJsSettings
  )
  .dependsOn(core)

// json

lazy val circeJson: ProjectMatrix = (projectMatrix in file("json/circe"))
  .settings(commonSettings)
  .settings(
    name := "tapir-json-circe",
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % Versions.circe,
      "io.circe" %%% "circe-parser" % Versions.circe,
      "io.circe" %%% "circe-generic" % Versions.circe,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings
  )
  .dependsOn(core)

lazy val json4s: ProjectMatrix = (projectMatrix in file("json/json4s"))
  .settings(commonSettings)
  .settings(
    name := "tapir-json-json4s",
    libraryDependencies ++= Seq(
      "org.json4s" %%% "json4s-core" % Versions.json4s,
      "org.json4s" %%% "json4s-jackson" % Versions.json4s % Test,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(core)

lazy val playJson: ProjectMatrix = (projectMatrix in file("json/playjson"))
  .settings(commonSettings: _*)
  .settings(
    name := "tapir-json-play",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %%% "play-json" % Versions.playJson,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .jsPlatform(
    scalaVersions = scala2Versions,
    settings = commonJsSettings ++ Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time" % Versions.jsScalaJavaTime % Test
      )
    )
  )
  .dependsOn(core)

lazy val sprayJson: ProjectMatrix = (projectMatrix in file("json/sprayjson"))
  .settings(commonSettings: _*)
  .settings(
    name := "tapir-json-spray",
    libraryDependencies ++= Seq(
      "io.spray" %% "spray-json" % Versions.sprayJson,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(core)

lazy val uPickleJson: ProjectMatrix = (projectMatrix in file("json/upickle"))
  .settings(commonSettings)
  .settings(
    name := "tapir-json-upickle",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % Versions.upickle,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings ++ Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time" % Versions.jsScalaJavaTime % Test
      )
    )
  )
  .nativePlatform(
    scalaVersions = scala2And3Versions,
    settings = commonNativeSettings ++ Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time" % Versions.nativeScalaJavaTime % Test
      )
    )
  )
  .dependsOn(core)

lazy val tethysJson: ProjectMatrix = (projectMatrix in file("json/tethys"))
  .settings(commonSettings)
  .settings(
    name := "tapir-json-tethys",
    libraryDependencies ++= Seq(
      "com.tethys-json" %% "tethys-core" % Versions.tethys,
      "com.tethys-json" %% "tethys-jackson" % Versions.tethys,
      scalaTest.value % Test,
      "com.tethys-json" %% "tethys-derivation" % Versions.tethys % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(core)

lazy val jsoniterScala: ProjectMatrix = (projectMatrix in file("json/jsoniter"))
  .settings(commonSettings)
  .settings(
    name := "tapir-jsoniter-scala",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core" % "2.13.36",
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % "2.13.36" % Test,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings
  )
  .nativePlatform(
    scalaVersions = scala2And3Versions,
    settings = commonNativeSettings
  )
  .dependsOn(core)

lazy val zio1Json: ProjectMatrix = (projectMatrix in file("json/zio1"))
  .settings(commonSettings)
  .settings(
    name := "tapir-json-zio1",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % Versions.zio1Json,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(
    scalaVersions = scala2Versions,
    settings = commonJsSettings
  )
  .dependsOn(core)

lazy val zioJson: ProjectMatrix = (projectMatrix in file("json/zio"))
  .settings(commonSettings)
  .settings(
    name := "tapir-json-zio",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % Versions.zioJson,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings
  )
  .dependsOn(core)

// metrics

lazy val prometheusMetrics: ProjectMatrix = (projectMatrix in file("metrics/prometheus-metrics"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-prometheus-metrics",
    libraryDependencies ++= Seq(
      "io.prometheus" % "simpleclient_common" % "0.16.0",
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore % CompileAndTest)

lazy val opentelemetryMetrics: ProjectMatrix = (projectMatrix in file("metrics/opentelemetry-metrics"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-opentelemetry-metrics",
    libraryDependencies ++= Seq(
      "io.opentelemetry" % "opentelemetry-api" % Versions.openTelemetry,
      "io.opentelemetry" % "opentelemetry-sdk" % Versions.openTelemetry % Test,
      "io.opentelemetry" % "opentelemetry-sdk-testing" % Versions.openTelemetry % Test,
      "io.opentelemetry" % "opentelemetry-sdk-metrics" % Versions.openTelemetry % Test,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore % CompileAndTest)

lazy val datadogMetrics: ProjectMatrix = (projectMatrix in file("metrics/datadog-metrics"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-datadog-metrics",
    libraryDependencies ++= Seq(
      "com.datadoghq" % "java-dogstatsd-client" % Versions.dogstatsdClient,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore % CompileAndTest)

// docs

lazy val apispecDocs: ProjectMatrix = (projectMatrix in file("docs/apispec-docs"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-apispec-docs",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.apispec" %% "asyncapi-model" % Versions.sttpApispec
    )
  )
  .jvmPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings
  )
  .dependsOn(core, tests % Test)

lazy val openapiDocs: ProjectMatrix = (projectMatrix in file("docs/openapi-docs"))
  .settings(commonSettings)
  .settings(
    name := "tapir-openapi-docs",
    libraryDependencies ++= Seq(
      "com.softwaremill.quicklens" %%% "quicklens" % Versions.quicklens,
      "com.softwaremill.sttp.apispec" %% "openapi-model" % Versions.sttpApispec,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % Versions.sttpApispec % Test
    )
  )
  .jvmPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings
  )
  .dependsOn(core, apispecDocs, tests % Test)

lazy val openapiDocs3 = openapiDocs.jvm(scala3).dependsOn()
lazy val openapiDocs2_13 = openapiDocs.jvm(scala2_13).dependsOn(enumeratum.jvm(scala2_13))
lazy val openapiDocs2_12 = openapiDocs.jvm(scala2_12).dependsOn(enumeratum.jvm(scala2_12))

lazy val asyncapiDocs: ProjectMatrix = (projectMatrix in file("docs/asyncapi-docs"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-asyncapi-docs",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.apispec" %% "asyncapi-model" % Versions.sttpApispec,
      "com.softwaremill.sttp.apispec" %% "asyncapi-circe-yaml" % Versions.sttpApispec % Test,
      "com.typesafe.akka" %% "akka-stream" % Versions.akkaStreams % Test,
      "com.softwaremill.sttp.shared" %% "akka" % Versions.sttpShared % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(core, apispecDocs, tests % Test)

lazy val swaggerUi: ProjectMatrix = (projectMatrix in file("docs/swagger-ui"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-swagger-ui",
    libraryDependencies ++= Seq("org.webjars" % "swagger-ui" % Versions.swaggerUi)
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(core)

lazy val swaggerUiBundle: ProjectMatrix = (projectMatrix in file("docs/swagger-ui-bundle"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-swagger-ui-bundle",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % Versions.sttpApispec,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer % Test,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(swaggerUi, openapiDocs, sttpClient % Test, http4sServer % Test)

lazy val redoc: ProjectMatrix = (projectMatrix in file("docs/redoc"))
  .settings(commonSettings)
  .settings(name := "tapir-redoc")
  .jvmPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings
  )
  .dependsOn(core)

lazy val redocBundle: ProjectMatrix = (projectMatrix in file("docs/redoc-bundle"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-redoc-bundle",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % Versions.sttpApispec,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer % Test,
      scalaTest.value % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(redoc, openapiDocs, sttpClient % Test, http4sServer % Test)

// server

lazy val serverCore: ProjectMatrix = (projectMatrix in file("server/core"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-server",
    description := "Core classes for server interpreters & interceptors",
    libraryDependencies ++= Seq(scalaTest.value % Test)
  )
  .dependsOn(core % CompileAndTest)
  .jvmPlatform(scalaVersions = scala2And3Versions, settings = commonJvmSettings)
  .jsPlatform(scalaVersions = scala2And3Versions, settings = commonJsSettings)
  .nativePlatform(scalaVersions = scala2And3Versions, settings = commonNativeSettings)

lazy val serverTests: ProjectMatrix = (projectMatrix in file("server/tests"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-server-tests",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "fs2" % Versions.sttp
    )
  )
  .dependsOn(tests, sttpStubServer)
  .jvmPlatform(scalaVersions = scala2And3Versions)

lazy val akkaHttpServer: ProjectMatrix = (projectMatrix in file("server/akka-http-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-akka-http-server",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % Versions.akkaHttp,
      "com.typesafe.akka" %% "akka-stream" % Versions.akkaStreams,
      "com.typesafe.akka" %% "akka-slf4j" % Versions.akkaStreams,
      "com.softwaremill.sttp.shared" %% "akka" % Versions.sttpShared,
      "com.softwaremill.sttp.client3" %% "akka-http-backend" % Versions.sttp % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(serverCore, serverTests % Test)

lazy val armeriaServer: ProjectMatrix = (projectMatrix in file("server/armeria-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-armeria-server",
    libraryDependencies ++= Seq(
      "com.linecorp.armeria" % "armeria" % Versions.armeria,
      "org.scala-lang.modules" %% "scala-java8-compat" % Versions.scalaJava8Compat,
      "com.softwaremill.sttp.shared" %% "armeria" % Versions.sttpShared
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, serverTests % Test)

lazy val armeriaServerCats: ProjectMatrix =
  (projectMatrix in file("server/armeria-server/cats"))
    .settings(commonJvmSettings)
    .settings(
      name := "tapir-armeria-server-cats",
      libraryDependencies ++= Seq(
        "com.softwaremill.sttp.shared" %% "fs2" % Versions.sttpShared,
        "co.fs2" %% "fs2-reactive-streams" % Versions.fs2
      )
    )
    .jvmPlatform(scalaVersions = scala2And3Versions)
    .dependsOn(armeriaServer % CompileAndTest, cats, serverTests % Test)

lazy val armeriaServerZio: ProjectMatrix =
  (projectMatrix in file("server/armeria-server/zio"))
    .settings(commonJvmSettings)
    .settings(
      name := "tapir-armeria-server-zio",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-interop-reactivestreams" % Versions.zioInteropReactiveStreams
      )
    )
    .jvmPlatform(scalaVersions = scala2And3Versions)
    .dependsOn(armeriaServer % CompileAndTest, zio, serverTests % Test)

lazy val armeriaServerZio1: ProjectMatrix =
  (projectMatrix in file("server/armeria-server/zio1"))
    .settings(commonJvmSettings)
    .settings(
      name := "tapir-armeria-server-zio1",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-interop-reactivestreams" % Versions.zio1InteropReactiveStreams
      )
    )
    .jvmPlatform(scalaVersions = scala2And3Versions)
    .dependsOn(armeriaServer % CompileAndTest, zio1, serverTests % Test)

lazy val http4sServer: ProjectMatrix = (projectMatrix in file("server/http4s-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-http4s-server",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-server" % Versions.http4s,
      "com.softwaremill.sttp.shared" %% "fs2" % Versions.sttpShared,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, cats, serverTests % Test)

lazy val http4sServerZio1: ProjectMatrix = (projectMatrix in file("server/http4s-server/zio1"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-http4s-server-zio1",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-interop-cats" % Versions.zio1InteropCats,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(zio1, http4sServer, serverTests % Test)

lazy val http4sServerZio: ProjectMatrix = (projectMatrix in file("server/http4s-server/zio"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-http4s-server-zio",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-interop-cats" % Versions.zioInteropCats,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(zio, http4sServer, serverTests % Test)

lazy val sttpStubServer: ProjectMatrix = (projectMatrix in file("server/sttp-stub-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-sttp-stub-server"
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, sttpClient, tests % Test)

lazy val sttpMockServer: ProjectMatrix = (projectMatrix in file("server/sttp-mock-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "sttp-mock-server",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %%% "core" % Versions.sttp,
      "io.circe" %% "circe-core" % Versions.circe,
      "io.circe" %% "circe-parser" % Versions.circe,
      "io.circe" %% "circe-generic" % Versions.circe,
      // test libs
      "io.circe" %% "circe-literal" % Versions.circe % Test,
      "org.mock-server" % "mockserver-netty-no-dependencies" % Versions.mockServer % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(serverCore, serverTests % "test", sttpClient)

lazy val finatraServer: ProjectMatrix = (projectMatrix in file("server/finatra-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-finatra-server",
    libraryDependencies ++= Seq(
      "com.twitter" %% "finatra-http-server" % Versions.finatra,
      "org.apache.httpcomponents" % "httpmime" % "4.5.13",
      // Testing
      "com.twitter" %% "inject-server" % Versions.finatra % Test,
      "com.twitter" %% "inject-app" % Versions.finatra % Test,
      "com.twitter" %% "inject-core" % Versions.finatra % Test,
      "com.twitter" %% "inject-modules" % Versions.finatra % Test,
      "com.twitter" %% "finatra-http-server" % Versions.finatra % Test classifier "tests",
      "com.twitter" %% "inject-server" % Versions.finatra % Test classifier "tests",
      "com.twitter" %% "inject-app" % Versions.finatra % Test classifier "tests",
      "com.twitter" %% "inject-core" % Versions.finatra % Test classifier "tests",
      "com.twitter" %% "inject-modules" % Versions.finatra % Test classifier "tests"
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(serverCore, serverTests % Test)

lazy val finatraServerCats: ProjectMatrix =
  (projectMatrix in file("server/finatra-server/cats"))
    .settings(commonJvmSettings)
    .settings(name := "tapir-finatra-server-cats")
    .jvmPlatform(scalaVersions = scala2Versions)
    .dependsOn(finatraServer % CompileAndTest, cats, serverTests % Test)

lazy val playServer: ProjectMatrix = (projectMatrix in file("server/play-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-play-server",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-server" % Versions.playServer,
      "com.typesafe.play" %% "play-akka-http-server" % Versions.playServer,
      "com.typesafe.play" %% "play" % Versions.playServer,
      "com.softwaremill.sttp.shared" %% "akka" % Versions.sttpShared,
      "org.scala-lang.modules" %% "scala-collection-compat" % Versions.scalaCollectionCompat
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(serverCore, serverTests % Test)

lazy val nettyServer: ProjectMatrix = (projectMatrix in file("server/netty-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-netty-server",
    libraryDependencies ++= Seq(
      "io.netty" % "netty-all" % "4.1.79.Final"
    ) ++ loggerDependencies,
    // needed because of https://github.com/coursier/coursier/issues/2016
    useCoursier := false
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, serverTests % Test)

lazy val nettyServerCats: ProjectMatrix = (projectMatrix in file("server/netty-server/cats"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-netty-server-cats",
    libraryDependencies ++= Seq(
      "io.netty" % "netty-all" % "4.1.79.Final",
      "com.softwaremill.sttp.shared" %% "fs2" % Versions.sttpShared
    ) ++ loggerDependencies,
    // needed because of https://github.com/coursier/coursier/issues/2016
    useCoursier := false
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, nettyServer, cats, serverTests % Test)

lazy val vertxServer: ProjectMatrix = (projectMatrix in file("server/vertx-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-vertx-server",
    libraryDependencies ++= Seq(
      "io.vertx" % "vertx-web" % Versions.vertx
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, serverTests % Test)

lazy val vertxServerCats: ProjectMatrix = (projectMatrix in file("server/vertx-server/cats"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-vertx-server-cats",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-reactive-streams" % Versions.fs2,
      "com.softwaremill.sttp.shared" %% "fs2" % Versions.sttpShared
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, vertxServer % CompileAndTest, serverTests % Test)

lazy val vertxServerZio: ProjectMatrix = (projectMatrix in file("server/vertx-server/zio"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-vertx-server-zio",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-interop-cats" % Versions.zioInteropCats % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, vertxServer % CompileAndTest, zio, serverTests % Test)

lazy val vertxServerZio1: ProjectMatrix = (projectMatrix in file("server/vertx-server/zio1"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-vertx-server-zio1",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-interop-cats" % Versions.zio1InteropCats % Test
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, vertxServer % CompileAndTest, zio1, serverTests % Test)

lazy val zio1HttpServer: ProjectMatrix = (projectMatrix in file("server/zio1-http-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-zio1-http-server",
    libraryDependencies ++= Seq("dev.zio" %% "zio-interop-cats" % Versions.zio1InteropCats % Test, "io.d11" %% "zhttp" % "1.0.0.0-RC29")
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, zio1, serverTests % Test)

lazy val zioHttpServer: ProjectMatrix = (projectMatrix in file("server/zio-http-server"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-zio-http-server",
    libraryDependencies ++= Seq("dev.zio" %% "zio-interop-cats" % Versions.zioInteropCats % Test, "io.d11" %% "zhttp" % "2.0.0-RC10")
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(serverCore, zio, serverTests % Test)

// serverless

lazy val awsLambda: ProjectMatrix = (projectMatrix in file("serverless/aws/lambda"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-aws-lambda",
    libraryDependencies ++= loggerDependencies,
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "fs2" % Versions.sttp
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(scalaVersions = scala2Versions)
  .dependsOn(serverCore, cats, circeJson, tests % "test")

// integration tests for lambda interpreter
// it's a separate project since it needs a fat jar with lambda code which cannot be build from tests sources
// runs sam local cmd line tool to start AWS Api Gateway with lambda proxy
lazy val awsLambdaTests: ProjectMatrix = (projectMatrix in file("serverless/aws/lambda-tests"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-aws-lambda-tests",
    libraryDependencies += "com.amazonaws" % "aws-lambda-java-runtime-interface-client" % Versions.awsLambdaInterface,
    assembly / assemblyJarName := "tapir-aws-lambda-tests.jar",
    assembly / test := {}, // no tests before building jar
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "io.netty.versions.properties")                    => MergeStrategy.first
      case PathList(ps @ _*) if ps.last contains "FlowAdapters"                    => MergeStrategy.first
      case _ @("scala/annotation/nowarn.class" | "scala/annotation/nowarn$.class") => MergeStrategy.first
      case x                                                                       => (assembly / assemblyMergeStrategy).value(x)
    },
    Test / test := {
      if (scalaVersion.value == scala2_13) { // only one test can run concurrently, as it starts a local sam instance
        (Test / test)
          .dependsOn(
            Def.sequential(
              (Compile / runMain).toTask(" sttp.tapir.serverless.aws.lambda.tests.LambdaSamTemplate"),
              assembly
            )
          )
          .value
      }
    },
    Test / testOptions ++= {
      val log = sLog.value
      // process uses template.yaml which is generated by `LambdaSamTemplate` called above
      lazy val sam = Process("sam local start-api --warm-containers EAGER").run()
      Seq(
        Tests.Setup(() => {
          val samReady = PollingUtils.poll(60.seconds, 1.second) {
            sam.isAlive() && PollingUtils.urlConnectionAvailable(new URL(s"http://127.0.0.1:3000/health"))
          }
          if (!samReady) {
            sam.destroy()
            val exit = sam.exitValue()
            log.error(s"failed to start sam local within 60 seconds (exit code: $exit")
          }
        }),
        Tests.Cleanup(() => {
          sam.destroy()
          val exit = sam.exitValue()
          log.info(s"stopped sam local (exit code: $exit")
        })
      )
    },
    Test / parallelExecution := false
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(core, cats, circeJson, awsLambda, awsSam, sttpStubServer, serverTests)

lazy val awsSam: ProjectMatrix = (projectMatrix in file("serverless/aws/sam"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-aws-sam",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-yaml" % Versions.circeYaml,
      "io.circe" %% "circe-generic" % Versions.circe
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(core, tests % Test)

lazy val awsTerraform: ProjectMatrix = (projectMatrix in file("serverless/aws/terraform"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-aws-terraform",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-yaml" % Versions.circeYaml,
      "io.circe" %% "circe-generic" % Versions.circe,
      "io.circe" %% "circe-literal" % Versions.circe,
      "org.typelevel" %% "jawn-parser" % "1.4.0"
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(core, tests % Test)

lazy val awsExamples: ProjectMatrix = (projectMatrix in file("serverless/aws/examples"))
  .settings(commonSettings)
  .settings(
    name := "tapir-aws-examples",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %%% "cats" % Versions.sttp
    )
  )
  .jvmPlatform(
    scalaVersions = scala2Versions,
    settings = commonJvmSettings ++ Seq(
      assembly / assemblyJarName := "tapir-aws-examples.jar",
      assembly / assemblyMergeStrategy := {
        case PathList("META-INF", "io.netty.versions.properties")                    => MergeStrategy.first
        case PathList(ps @ _*) if ps.last contains "FlowAdapters"                    => MergeStrategy.first
        case _ @("scala/annotation/nowarn.class" | "scala/annotation/nowarn$.class") => MergeStrategy.first
        case x                                                                       => (assembly / assemblyMergeStrategy).value(x)
      },
      libraryDependencies += "com.amazonaws" % "aws-lambda-java-runtime-interface-client" % Versions.awsLambdaInterface
    )
  )
  .jsPlatform(
    scalaVersions = scala2Versions,
    settings = commonJsSettings ++ Seq(
      scalaJSUseMainModuleInitializer := false,
      scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
    )
  )
  .dependsOn(awsLambda)

lazy val awsExamples2_12 = awsExamples.jvm(scala2_12).dependsOn(awsSam.jvm(scala2_12), awsTerraform.jvm(scala2_12))
lazy val awsExamples2_13 = awsExamples.jvm(scala2_13).dependsOn(awsSam.jvm(scala2_13), awsTerraform.jvm(scala2_13))

// client

lazy val clientTests: ProjectMatrix = (projectMatrix in file("client/tests"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-client-tests",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % Versions.http4s,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer,
      "org.http4s" %% "http4s-circe" % Versions.http4s
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings
  )
  .dependsOn(tests)

lazy val clientCore: ProjectMatrix = (projectMatrix in file("client/core"))
  .settings(commonSettings)
  .settings(
    name := "tapir-client",
    description := "Core classes for client interpreters",
    libraryDependencies ++= Seq(scalaTest.value % Test)
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .jsPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(core)

lazy val http4sClient: ProjectMatrix = (projectMatrix in file("client/http4s-client"))
  .settings(clientTestServerSettings)
  .settings(commonSettings)
  .settings(
    name := "tapir-http4s-client",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % Versions.http4s,
      "org.http4s" %% "http4s-blaze-client" % Versions.http4sBlazeClient % Test,
      "com.softwaremill.sttp.shared" %% "fs2" % Versions.sttpShared % Optional
    )
  )
  .jvmPlatform(scalaVersions = scala2And3Versions)
  .dependsOn(clientCore, clientTests % Test)

lazy val sttpClient: ProjectMatrix = (projectMatrix in file("client/sttp-client"))
  .settings(clientTestServerSettings)
  .settings(
    name := "tapir-sttp-client",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %%% "core" % Versions.sttp
    )
  )
  .jvmPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJvmSettings ++ Seq(
      libraryDependencies ++= Seq(
        "com.softwaremill.sttp.client3" %% "fs2" % Versions.sttp % Test,
        "com.softwaremill.sttp.client3" %% "zio" % Versions.sttp % Test,
        "com.softwaremill.sttp.shared" %% "fs2" % Versions.sttpShared % Optional,
        "com.softwaremill.sttp.shared" %% "zio" % Versions.sttpShared % Optional
      ),
      libraryDependencies ++= {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((3, _)) => Nil
          case _ =>
            Seq(
              "com.softwaremill.sttp.shared" %% "akka" % Versions.sttpShared % Optional,
              "com.softwaremill.sttp.client3" %% "akka-http-backend" % Versions.sttp % Test,
              "com.typesafe.akka" %% "akka-stream" % Versions.akkaStreams % Optional
            )
        }
      }
    )
  )
  .jsPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJsSettings ++ Seq(
      libraryDependencies ++= Seq(
        "io.github.cquiroz" %%% "scala-java-time" % Versions.jsScalaJavaTime % Test
      )
    )
  )
  .dependsOn(clientCore, clientTests % Test)

lazy val sttpClientWsZio1: ProjectMatrix = (projectMatrix in file("client/sttp-client-ws-zio1"))
  .settings(clientTestServerSettings)
  .settings(
    name := "tapir-sttp-client-ws-zio1"
  )
  .jvmPlatform(
    scalaVersions = scala2And3Versions,
    settings = commonJvmSettings ++ Seq(
      libraryDependencies ++= Seq(
        "com.softwaremill.sttp.client3" %% "zio1" % Versions.sttp % Test,
        "com.softwaremill.sttp.shared" %% "zio1" % Versions.sttpShared
      )
    )
  )
  .dependsOn(sttpClient, clientTests % Test)

lazy val playClient: ProjectMatrix = (projectMatrix in file("client/play-client"))
  .settings(clientTestServerSettings)
  .settings(commonSettings)
  .settings(
    name := "tapir-play-client",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ahc-ws-standalone" % Versions.playClient,
      "com.softwaremill.sttp.shared" %% "akka" % Versions.sttpShared % Optional,
      "com.typesafe.akka" %% "akka-stream" % Versions.akkaStreams % Optional
    )
  )
  .jvmPlatform(scalaVersions = scala2Versions)
  .dependsOn(clientCore, clientTests % Test)

import scala.collection.JavaConverters._

lazy val openapiCodegenCore: ProjectMatrix = (projectMatrix in file("openapi-codegen/core"))
  .settings(commonSettings)
  .jvmPlatform(scalaVersions = codegenScalaVersions)
  .settings(
    name := "tapir-openapi-codegen-core",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % Versions.circe,
      "io.circe" %% "circe-generic" % Versions.circe,
      "io.circe" %% "circe-yaml" % Versions.circeYaml,
      scalaTest.value % Test,
      scalaCheck.value % Test,
      scalaTestPlusScalaCheck.value % Test,
      "com.47deg" %% "scalacheck-toolbox-datetime" % "0.6.0" % Test,
      scalaOrganization.value % "scala-reflect" % scalaVersion.value,
      scalaOrganization.value % "scala-compiler" % scalaVersion.value % Test
    )
  )
  .dependsOn(core % Test, circeJson % Test)

lazy val openapiCodegenSbt: ProjectMatrix = (projectMatrix in file("openapi-codegen/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(commonSettings)
  .jvmPlatform(scalaVersions = codegenScalaVersions)
  .settings(
    name := "sbt-openapi-codegen",
    sbtPlugin := true,
    scriptedLaunchOpts += ("-Dplugin.version=" + version.value),
    scriptedLaunchOpts ++= java.lang.management.ManagementFactory.getRuntimeMXBean.getInputArguments.asScala
      .filter(a => Seq("-Xmx", "-Xms", "-XX", "-Dfile").exists(a.startsWith)),
    scriptedBufferLog := false,
    sbtTestDirectory := sourceDirectory.value / "sbt-test",
    libraryDependencies ++= Seq(
      scalaTest.value % Test,
      scalaCheck.value % Test,
      scalaTestPlusScalaCheck.value % Test,
      "com.47deg" %% "scalacheck-toolbox-datetime" % "0.6.0" % Test,
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % Test
    )
  )
  .dependsOn(openapiCodegenCore, core % Test, circeJson % Test)

lazy val openapiCodegenCli: ProjectMatrix = (projectMatrix in file("openapi-codegen/cli"))
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .jvmPlatform(scalaVersions = codegenScalaVersions)
  .settings(
    name := "tapir-codegen",
    buildInfoPackage := "sttp.tapir.codegen",
    libraryDependencies ++= Seq(
      "com.monovore" %% "decline" % Versions.decline,
      "com.monovore" %% "decline-effect" % Versions.decline,
      "org.scala-lang.modules" %% "scala-collection-compat" % Versions.scalaCollectionCompat
    )
  )
  .dependsOn(openapiCodegenCore, core % Test, circeJson % Test)

// other

lazy val examples: ProjectMatrix = (projectMatrix in file("examples"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-examples",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-interop-cats" % Versions.zioInteropCats,
      "org.typelevel" %% "cats-effect" % Versions.catsEffect,
      "org.http4s" %% "http4s-dsl" % Versions.http4s,
      "org.http4s" %% "http4s-circe" % Versions.http4s,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer,
      "com.softwaremill.sttp.client3" %% "akka-http-backend" % Versions.sttp,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-fs2" % Versions.sttp,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % Versions.sttp,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % Versions.sttp,
      "com.softwaremill.sttp.apispec" %% "asyncapi-circe-yaml" % Versions.sttpApispec,
      "com.pauldijou" %% "jwt-circe" % Versions.jwtScala,
      "org.mock-server" % "mockserver-netty-no-dependencies" % Versions.mockServer,
      scalaTest.value
    ),
    libraryDependencies ++= loggerDependencies,
    publishArtifact := false
  )
  .jvmPlatform(scalaVersions = examplesScalaVersions)
  .dependsOn(
    akkaHttpServer,
    armeriaServer,
    http4sServer,
    http4sServerZio,
    http4sClient,
    sttpClient,
    openapiDocs,
    asyncapiDocs,
    circeJson,
    swaggerUiBundle,
    redocBundle,
    zioHttpServer,
    nettyServer,
    nettyServerCats,
    sttpStubServer,
    playJson,
    prometheusMetrics,
    opentelemetryMetrics,
    datadogMetrics,
    sttpMockServer,
    zioJson,
    vertxServer,
    vertxServerCats,
    vertxServerZio,
    finatraServer
  )

lazy val examples3: ProjectMatrix = (projectMatrix in file("examples3"))
  .settings(commonJvmSettings)
  .settings(
    name := "tapir-examples3",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer,
      "com.softwaremill.sttp.client3" %% "core" % Versions.sttp
    ),
    libraryDependencies ++= loggerDependencies,
    publishArtifact := false
  )
  .jvmPlatform(scalaVersions = List(scala3))
  .dependsOn(
    http4sServer,
    swaggerUiBundle,
    circeJson
  )

//TODO this should be invoked by compilation process, see #https://github.com/scalameta/mdoc/issues/355
val compileDocumentation: TaskKey[Unit] = taskKey[Unit]("Compiles documentation throwing away its output")
compileDocumentation := {
  (documentation.jvm(documentationScalaVersion) / mdoc).toTask(" --out target/tapir-doc").value
}

lazy val documentation: ProjectMatrix = (projectMatrix in file("generated-doc")) // important: it must not be doc/
  .enablePlugins(MdocPlugin)
  .settings(commonSettings)
  .settings(macros)
  .settings(
    mdocIn := file("doc"),
    moduleName := "tapir-doc",
    mdocVariables := Map(
      "VERSION" -> version.value,
      "PLAY_HTTP_SERVER_VERSION" -> Versions.playServer,
      "JSON4S_VERSION" -> Versions.json4s
    ),
    mdocOut := file("generated-doc/out"),
    mdocExtraArguments := Seq("--clean-target"),
    publishArtifact := false,
    name := "doc",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-netty-server" % Versions.playServer,
      "org.http4s" %% "http4s-blaze-server" % Versions.http4sBlazeServer,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % Versions.sttpApispec,
      "com.softwaremill.sttp.apispec" %% "asyncapi-circe-yaml" % Versions.sttpApispec
    ),
    // needed because of https://github.com/coursier/coursier/issues/2016
    useCoursier := false
  )
  .jvmPlatform(scalaVersions = List(documentationScalaVersion))
  .dependsOn(
    core % "compile->test",
    testing,
    akkaHttpServer,
    armeriaServer,
    armeriaServerCats,
    armeriaServerZio,
    armeriaServerZio1,
    circeJson,
    enumeratum,
    finatraServer,
    finatraServerCats,
    jsoniterScala,
    asyncapiDocs,
    openapiDocs,
    json4s,
    playJson,
    playServer,
    sprayJson,
    http4sClient,
    http4sServerZio,
    sttpClient,
    playClient,
    sttpStubServer,
    tethysJson,
    uPickleJson,
    vertxServer,
    vertxServerCats,
    vertxServerZio,
    zio,
    zioHttpServer,
    derevo,
    zioJson,
    prometheusMetrics,
    opentelemetryMetrics,
    datadogMetrics,
    sttpMockServer,
    nettyServer,
    swaggerUiBundle
  )
