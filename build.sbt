lazy val root = (project in file("."))
  .settings(projectSettings: _*)
  .settings(libraryDependencies ++= projectDependencies)

lazy val projectSettings = Seq(
  name := "vantageloops",
  version := "0.1.1",
  organization := "me.mpasa",
  scalaVersion := "2.13.18"
)

val AKKA_SERIAL_VERSION = "4.2.0"

lazy val projectDependencies = Seq(
  // Serial
  "ch.jodersky" %% "akka-serial-core" % AKKA_SERIAL_VERSION,
  "ch.jodersky" % "akka-serial-native" % AKKA_SERIAL_VERSION,
  "ch.jodersky" %% "akka-serial-stream" % AKKA_SERIAL_VERSION,
  // Unit conversion
  "org.typelevel" %% "squants" % "1.8.3",
  // Scheduling
  "io.monix" %% "monix" % "3.4.1"
)
