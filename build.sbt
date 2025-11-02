Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val scala3 = "3.7.3"

ThisBuild / scalaVersion := scala3
ThisBuild / crossScalaVersions := Seq(scala3)
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// disables publish step
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.zulu("21"))

lazy val V = new {
  val circe = "0.14.6"
  val cats = "2.12.0"
  val fs2 = "3.10.2"
  val kittens = "3.2.0"
  val literally = "1.2.0"
  val mouse = "1.3.0"
  val ff4s = "0.26.1"
  val http4s = "0.23.27"
  val monocle = "3.3.0"
  val logback = "1.5.7"
  val test = "0.7.29"
  val `cats-effect` = "3.5.4"
  val `cats-time` = "0.5.1"
  val `scala-java-time` = "2.5.0"
  val `ff4s-shoelace` = "0.0.1"
  val `ff4s-heroicons` = "0.0.1"
  val `ff4s-canvas` = "0.0.1"
  val `log-4cats` = "2.7.0"
  val `commons-math` = "3.6.1"
}

lazy val root =
  (project in file(".")).aggregate(dtos.jvm, lib, backend, frontend)

lazy val dtos = crossProject(JSPlatform, JVMPlatform)
  .in(file("dtos"))
  .settings(
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++=
      Seq(
        "io.circe" %% "circe-core" % V.circe,
        "io.circe" %% "circe-generic" % V.circe,
        "io.circe" %% "circe-literal" % V.circe,
        "io.circe" %% "circe-parser" % V.circe
      ),
    scalacOptions -= "-Xfatal-warnings"
  ).dependsOn(`lib-dtos`)


lazy val frontend = project
  .in(file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "mouse" % V.mouse,
      "dev.optics" %%% "monocle-core" % V.monocle,
      "io.github.buntec" %%% "ff4s" % V.ff4s,
      "io.github.buntec" %%% "ff4s-canvas" % V.`ff4s-canvas`,
      "io.github.buntec" %%% "ff4s-shoelace" % V.`ff4s-shoelace`,
      "io.github.buntec" %%% "ff4s-heroicons" % V.`ff4s-heroicons`,
      "io.github.cquiroz" %%% "scala-java-time" % V.`scala-java-time`
    ),
    scalacOptions -= "-Xfatal-warnings"
  )
  .dependsOn(dtos.js)

lazy val backend = project
  .in(file("backend"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    fork := true,
    libraryDependencies ++=
      Seq(
        "ch.qos.logback" % "logback-classic" % V.logback,
        "io.circe" %% "circe-core" % V.circe,
        "io.circe" %% "circe-generic" % V.circe,
        "io.circe" %% "circe-literal" % V.circe,
        "io.circe" %% "circe-parser" % V.circe,
        "org.typelevel" %% "cats-core" % V.cats,
        "co.fs2" %% "fs2-core" % V.fs2,
        "co.fs2" %% "fs2-io" % V.fs2,
        "org.typelevel" %% "kittens" % V.kittens,
        "org.typelevel" %% "mouse" % V.mouse,
        "org.typelevel" %% "cats-effect" % V.`cats-effect`,
        "org.typelevel" %% "cats-effect-std" % V.`cats-effect`,
        "org.typelevel" %% "cats-time" % V.`cats-time`,
        "org.typelevel" %% "literally" % V.literally,
        "org.typelevel" %% "log4cats-core" % V.`log-4cats`,
        "org.typelevel" %% "log4cats-slf4j" % V.`log-4cats`,
        "org.apache.commons" % "commons-math3" % V.`commons-math`
      ),
    scalacOptions -= "-Xfatal-warnings"
  )
  .dependsOn(dtos.jvm)
