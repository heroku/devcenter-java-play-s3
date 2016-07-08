import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "devcenter-java-play-s3"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      "com.amazonaws" % "aws-java-sdk" % "1.3.11",
      "postgresql" % "postgresql" % "9.4-1201-jdbc4"
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = JAVA).settings(
      // Add your own project settings here      
    )

}
