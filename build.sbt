val scalusVersion = "0.17.0+9-7492763a-SNAPSHOT"
// The scalus-plugin module uses CrossVersion.full so its artifact is
// `scalus-plugin_3.3.7` (full Scala version), referenced literally
// rather than via `%%`. Snapshot version tracks scalusVersion.
val scalusNodeVersion = "0.0.0+95-d9a8bac5+20260506-1220-SNAPSHOT"

ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots

// Latest Scala 3 LTS version
ThisBuild / scalaVersion := "3.3.7"

ThisBuild / scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked")

// Scalus compiler plugin — must be added to every subproject that has
// Scalus code (top-level `addCompilerPlugin` only attaches to the root
// project's compile classpath).
val scalusPluginSettings: Seq[Def.Setting[?]] = Seq(
  addCompilerPlugin("org.scalus" % "scalus-plugin_3.3.7" % scalusVersion)
)

val scalatestDeps = Seq(
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
  "org.scalacheck" %% "scalacheck" % "1.19.0" % Test
)

// Pure protocol library: validator, contract compilation, tx-builders,
// AppCtx, PythClient. No HTTP / Tapir / decline. Both `endpoints` and
// `liquid-bot` depend on this and nothing else from the repo.
lazy val commonLib = (project in file("common-lib"))
    .settings(scalusPluginSettings)
    .settings(
      libraryDependencies ++= Seq(
        "org.scalus" %% "scalus" % scalusVersion,
        "org.scalus" %% "scalus-cardano-ledger" % scalusVersion,
        // PythClient uses sttp's default Future backend; Backend[Future]
        // is exported from AppCtx.
        "com.softwaremill.sttp.client4" %% "core" % "4.0.10",
        "org.slf4j" % "slf4j-simple" % "2.0.17",
        "org.scalus" %% "scalus-testkit" % scalusVersion
      ) ++ scalatestDeps
    )

// HTTP server + CLI entry. Depends on commonLib for AppCtx and tx-builders.
lazy val endpoints = (project in file("endpoints"))
    .dependsOn(commonLib)
    .settings(scalusPluginSettings)
    .settings(
      run / fork := true,
      libraryDependencies ++= Seq(
        "com.softwaremill.sttp.tapir" %% "tapir-netty-server-sync" % "1.13.13",
        "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % "1.13.13",
        "com.softwaremill.sttp.tapir" %% "tapir-json-upickle" % "1.13.13",
        "com.monovore" %% "decline" % "2.6.1"
      ) ++ scalatestDeps
    )

// Liquidation bot — autonomous keeper that follows the chain via the embedded
// scalus-node and submits Liquidate transactions for under-collateralised CDPs.
// See liquid-bot/doc/design.md.
lazy val liquidBot = (project in file("liquid-bot"))
    .dependsOn(commonLib)
    .settings(scalusPluginSettings)
    .settings(
      publish / skip := true,
      run / fork := true,
      libraryDependencies ++= Seq(
        "org.scalus" %% "scalus-streaming-ox" % scalusNodeVersion,
        "org.scalus" %% "scalus-cardano-network" % scalusNodeVersion,
        "org.scalus" %% "scalus-chain-store-rocksdb" % scalusNodeVersion,
        // Mithril snapshot bootstrap for the embedded ChainStore. Pulls in
        // chicory (WASM runtime for the upstream Mithril cert verifier),
        // zstd-jni, bouncycastle, commons-compress — opt-in via the
        // `MithrilBootstrap` helper; not on the cold-start hot path.
        "org.scalus" %% "scalus-chain-store-mithril" % scalusNodeVersion,
        "com.monovore" %% "decline" % "2.6.1",
        "org.slf4j" % "slf4j-simple" % "2.0.17"
      ) ++ scalatestDeps
    )

// Integration tests — Yaci-DevKit and preprod scenarios. Depends on commonLib
// (for AppCtx, queries, tx-builders), endpoints in test scope (PreprodCdpTest
// hits the running HTTP server), and liquidBot in test scope (LiquidationFlowTest
// drives BotApp/Evaluator/PriceCache against a Yaci + fake-Lazer harness).
lazy val integration = (project in file("integration"))
    .dependsOn(
      commonLib % "compile->compile;test->test",
      endpoints % "test->compile",
      liquidBot % "test->compile"
    )
    .settings(scalusPluginSettings)
    .settings(
      publish / skip := true,
      // Default `integration/test` excludes preprod-only smoke tests so a clean
      // run doesn't need .env / Blockfrost credentials. Run them explicitly with
      //   sbt 'integration/testOnly *Preprod* -- -n pythacoin.integration.PreprodTag'
      Test / testOptions += Tests.Argument(
        TestFrameworks.ScalaTest, "-l", "pythacoin.integration.PreprodTag"
      ),
      libraryDependencies ++= Seq(
        "org.scalus" %% "scalus-testkit" % scalusVersion,
        "com.dimafeng" %% "testcontainers-scala-core" % "0.44.1" % Test,
        "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.44.1" % Test,
        "com.bloxbean.cardano" % "yaci-cardano-test" % "0.1.0" % Test,
        // Tiny pure-Java WS server for FakeLazerServer; the JDK only ships a
        // WS *client*. Test scope only — the production bot uses java.net.http.
        "org.java-websocket" % "Java-WebSocket" % "1.5.7" % Test
      ) ++ scalatestDeps
    )

lazy val root = (project in file("."))
    .aggregate(commonLib, endpoints, liquidBot, integration)
    .settings(publish / skip := true)
