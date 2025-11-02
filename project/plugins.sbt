lazy val V = new {
  val tpolecat = "0.5.1"
  val updates = "0.6.3"
  val `scala-fix` = "0.14.0"
  val `scala-fmt` = "2.5.4"
  val `scala-js` = "1.20.0"
  val `cross-project` = "1.3.2"
  val `native-packager` = "1.10.0"
  val `sbt-github-actions` = "0.28.0"
}

addSbtPlugin("org.typelevel" % "sbt-tpolecat" % V.tpolecat)
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % V.updates)
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % V.`scala-fix`)
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % V.`scala-fmt`)
addSbtPlugin("org.scala-js" % "sbt-scalajs" % V.`scala-js`)
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % V.`cross-project`)
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % V.`native-packager`)
addSbtPlugin("com.github.sbt" % "sbt-github-actions" % V.`sbt-github-actions`)
