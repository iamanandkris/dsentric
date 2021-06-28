import sbt.Keys._

lazy val buildSettings = Seq(
  organization       := "io.higherState",
  scalaVersion       := "2.13.1",
  version            := "1.0.0-RC2",
  scalacOptions     ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-language:implicitConversions", "-language:higherKinds", "-language:postfixOps", "-language:reflectiveCalls", "-language:existentials",
    "-unchecked",
    "-Xfatal-warnings",
    "-Ywarn-dead-code",
    "-Ywarn-value-discard"
  ),
  resolvers ++= Seq(
    DefaultMavenRepository,
    Resolver.typesafeIvyRepo("releases"),
    Resolver.sbtPluginRepo("releases"),
    Resolver.jcenterRepo,
    "Sonatype releases" at "http://oss.sonatype.org/content/repositories/releases/",
    "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"
  )
)

lazy val reflect = "org.scala-lang" % "scala-reflect" % "2.13.5"
lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.3"
lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.7" % "test"
lazy val cats = "org.typelevel" %% "cats-core" % "2.4.2"
lazy val commons_math = "org.apache.commons" % "commons-math3" % "3.6.1"


lazy val settings = buildSettings

lazy val core = project
  .settings(moduleName := "dsentric-core")
  .settings(settings)
  .settings(libraryDependencies ++= Seq(reflect, shapeless, scalatest, commons_math))


lazy val maps = project
  .settings(moduleName := "dsentric-maps")
  .settings(settings)
  .settings(libraryDependencies ++= Seq(reflect, shapeless, scalatest, cats))
  .dependsOn(core, core % "test -> test")

lazy val macros = project
  .settings(moduleName := "dsentric-macros")
  .settings(settings)
  .settings(  scalacOptions ++= Seq(
    "-Ymacro-annotations",
    "-Xmacro-settings:materialize-derivations",
    "-Ywarn-macros:after",
    "-Ywarn-unused:explicits",
    "-language:reflectiveCalls"
  ))
  .settings(libraryDependencies ++= Seq(reflect, shapeless, scalatest, commons_math))
  .dependsOn(core, maps)