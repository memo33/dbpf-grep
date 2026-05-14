name := "dbpf-cli"

ThisBuild / organization := "io.github.memo33"

ThisBuild / version := "0.1.1-SNAPSHOT"

// ThisBuild / versionScheme := Some("early-semver")

description := "Command-line tools for working with DBPF files"

ThisBuild / licenses += ("GPL-3.0-only", url("https://spdx.org/licenses/GPL-3.0-only.html"))

ThisBuild / scalaVersion := "3.8.2"

val minJavaVersion = settingKey[String]("minimum supported Java version")
ThisBuild / minJavaVersion := "17"

ThisBuild / scalacOptions ++= Seq(
  // "-Wunused:imports",
  "-unchecked",
  "-deprecation",
  "-feature",
  // "-opt-warnings:at-inline-failed-summary",
  // "-opt:l:inline", "-opt-inline-from:<sources>",
  "-Wvalue-discard",
  // "-source:future",
  "-encoding", "UTF-8",
  s"-release:${minJavaVersion.value}")

ThisBuild / javacOptions ++= Seq("--release", minJavaVersion.value)

// console / initialCommands := """
// """

Compile / mainClass := Some("io.github.memo33.dbpfcli.Main")

// Create a large executable jar with `sbt assembly`.
assembly / assemblyJarName := s"${name.value}.jar"

libraryDependencies ++= Seq(
  "com.github.alexarchambault" %% "case-app" % "2.1.0",  // command-line app helper
  "io.github.memo33" %% "scdbpf" % "0.3.0",
)
