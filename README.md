# sbt-dialog-api

SBT plugin to generate code from json Dialog API Schema

## Usage

### Adding the plugin dependency
In your project, create a file for plugin library dependencies `project/plugins.sbt` and add the following lines:

    addSbtPlugin("im.dlg" % "sbt-dialog-api" % "0.1.0-SNAPSHOT")

The dependency to `"com.google.protobuf" % "protobuf-java"` **is not** automatically added to the any of scopes.

### Importing sbt-dialog-api settings
To actually "activate" the plugin, its settings need to be included in the build.

##### build.sbt

    import im.dlg.SbtDialogApi

    Seq(SbtDialogApi.settings: _*)

##### build.scala
    import sbt._

    import im.dlg.SbtDialogApi

    object MyBuild extends Build {
      lazy val myproject = MyProject(
        id = "myproject",
        base = file("."),
        settings = Defaults.defaultSettings ++ SbtDialogApi.settings ++ Seq(
            /* custom settings here */
        )
      )
    }


### Compiling schema

Execute `dialog-schema-generate` in sbt.
