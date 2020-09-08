import AssemblyKeys._
import sbtassembly.AssemblyOption

// put this at the top of the file

name := "epic"

version := "0.4.4-SNAPSHOT"

organization := "org.scalanlp"

scalaVersion := "2.12.12"

crossScalaVersions  := Seq("2.12.12")

javacOptions ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-parameters")

scalacOptions ++= Seq("-encoding", "UTF-8","-deprecation", "-language:_", "-optimize", "-target:jvm-1.8")

resolvers ++= Seq(
  "ScalaNLP Maven2" at "http://repo.scalanlp.org/repo",
  "Scala Tools Snapshots" at "http://scala-tools.org/repo-snapshots/",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
)

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.5" % "test",
  "org.scalanlp" %% "breeze" % "0.13.2",
  "org.scalanlp" %% "breeze-config" % "0.9.2",
  "org.mapdb" % "mapdb" % "0.9.2",
  ("org.apache.tika" % "tika-parsers" % "1.5" % "provided").exclude ("edu.ucar", "netcdf").exclude("com.googlecode.mp4parser","isoparser"),
  "de.l3s.boilerpipe" % "boilerpipe" % "1.1.0" % "provided",
  "net.sourceforge.nekohtml" % "nekohtml" % "1.9.21" % "provided",
  "org.slf4j" % "slf4j-simple" % "1.7.25",
  "org.apache.commons" % "commons-lang3" % "3.3.2",
  "de.jflex" % "jflex" % "1.6.0" % "compile",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "org.scalacheck" %% "scalacheck" % "1.13.5" % "test",
  "org.scala-lang.modules" %% "scala-xml" % "1.1.0"
)


javaOptions += "-Xmx4g"

javaOptions += "-Xrunhprof:cpu=samples,depth=12"

fork := true

seq(assemblySettings: _*)

publishMavenStyle := true



pomExtra := (
  <url>http://scalanlp.org/</url>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.html</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:dlwh/epic.git</url>
    <connection>scm:git:git@github.com:dlwh/epic.git</connection>
  </scm>
  <developers>
    <developer>
      <id>dlwh</id>
      <name>David Hall</name>
      <url>http://cs.berkeley.edu/~dlwh/</url>
    </developer>
  </developers>)


// git.useGitDescribe := true

val VersionRegex = "v([0-9]+.[0-9]+.[0-9]+)-?(.*)?".r

publishTo <<= isSnapshot { (v: Boolean) =>
  val nexus = "https://oss.sonatype.org/"
  if (v)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}


publishArtifact in Test := false

pomIncludeRepository := { _ => false }

assemblyOption in assembly ~= { _.copy(cacheOutput = false) }

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case PathList("org", "w3c", "dom", _) => MergeStrategy.first
    case PathList("javax", "xml", "stream", _ *) => MergeStrategy.first
    case PathList("org", "cyberneko", "html", _ *) => MergeStrategy.first
    case x => old(x)
  }
}

excludedJars in assembly := {
  val cp = (fullClasspath in assembly).value
  cp filter { x =>
    x.data.getName == "pdfbox-1.8.4.jar" ||
    x.data.getName == "tika-core-1.5.jar" ||
    x.data.getName == "nekohtml-1.9.21.jar" ||
    x.data.getName == "boilerpipe-1.1.0.jar" ||
    x.data.getName == "slf4j-simple-1.7.25.jar" ||
    x.data.getName == "slf4j-api-1.7.25.jar"
  }
}



//excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
// cp filter {x => x.data.getName.matches(".*native.*") || x.data.getName.matches("sbt.*") || x.data.getName.matches(".*macros.*") }
//}
seq(sbtjflex.SbtJFlexPlugin.jflexSettings: _*)

net.virtualvoid.sbt.graph.Plugin.graphSettings
