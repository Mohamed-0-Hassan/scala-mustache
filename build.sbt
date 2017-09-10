name := "mustache"

version := "1.3"

scalaVersion := "2.12.2"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "Typesafe Snapshots Repository" at "http://repo.typesafe.com/typesafe/snapshots/"

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.11" % "test->default",
  "org.specs2" % "specs2_2.12" % "2.4.17" % "test->default",
  "com.typesafe.akka" %% "akka-actor" % "2.5.3" % "test->default"
)
