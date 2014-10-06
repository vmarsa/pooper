name := "pooper"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache,
  "com.typesafe.akka" %% "akka-actor" % "2.2.0",
  "org.specs2" %% "specs2" % "1.14" % "test",
  "commons-codec" % "commons-codec" % "1.7",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.0",
  "org.scalatest" % "scalatest_2.10" % "2.0" % "test",
  "com.google.guava" % "guava" % "16.0",
  "com.google.code.findbugs" % "jsr305" % "2.0.0"
)     

play.Project.playScalaSettings
