import scalariform.formatter.preferences._

sbtPlugin := true

organization := "im.dlg"

name := "sbt-dialog-api"

version := "0.0.8"

scalaVersion := "2.10.6"

scalacOptions ++= Seq("-deprecation", "-feature")

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases")
)

libraryDependencies ++= Seq(
  "com.eed3si9n" %% "treehugger" % "0.4.1",
  "io.spray" %% "spray-json" % "1.3.1",
  "org.specs2" %% "specs2-core" % "2.4.15" % "test"
)

scalariformSettings

ScalariformKeys.preferences :=
  ScalariformKeys.preferences.value
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)

publishMavenStyle := false
bintrayOrganization := Some("dialog")
bintrayRepository in bintray := "sbt-plugins"

pomExtra := (
  <url>http://github.com/dialogs/sbt-dialog-api</url>
  <licenses>
    <license>
      <name>MIT</name>
      <url>http://www.opensource.org/licenses/MIT</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:dialogs/sbt-dialog-api.git</url>
    <connection>scm:git:git@github.com:dialogs/sbt-dialog-api.git</connection>
  </scm>
  <developers>
    <developer>
      <id>prettynatty</id>
      <name>Andrey Kuznetsov</name>
      <url>http://fear.loathing.in</url>
    </developer>
  </developers>
)
