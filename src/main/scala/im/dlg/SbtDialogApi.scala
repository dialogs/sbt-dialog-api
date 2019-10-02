package im.dlg

import im.dlg.api._
import java.io.File
import sbt._, Keys._

object SbtDialogApi extends AutoPlugin {
  val DialogApi = config("dialogapi").hide

  val path = SettingKey[File]("dialog-schema-path", "The path that contains dialog.json file")
  val outputPath = SettingKey[File]("dialog-schema-output-path", "The paths where to save the generated *.scala files.")

  lazy val dialogApi = TaskKey[Seq[File]]("dialogapi", "Compile json schema to scala code")
  lazy val dialogApiClean = TaskKey[Seq[File]]("dialogapi-clean", "Clean generated code")

  lazy val dialogApiMain = SettingKey[String]("dialogapi-main", "dialogApi main class.")

  lazy val settings: Seq[Setting[_]] = Seq(
    sourceDirectory in DialogApi := (sourceDirectory in Compile).value,
    path := (sourceDirectory in DialogApi).value,
    managedClasspath in DialogApi :=  Classpaths.managedJars(DialogApi, classpathTypes.value, update.value),
    outputPath := (sourceManaged in DialogApi).value,

    dialogApi := generate(
      (sourceDirectory in DialogApi).value,
      (sourceManaged in DialogApi).value,
      (managedClasspath in DialogApi).value,
      javaHome.value,
      streams.value),

    dialogApiClean := clean(
      (sourceManaged in DialogApi).value,
      streams.value),

    sourceGenerators in Compile += dialogApi
  )

  private def compiledFileDir(targetDir: File): File =
    targetDir / "main" / "scala"

  private def compiledFile(targetDir: File, name: String): File =
    compiledFileDir(targetDir) / s"${name}.scala"

  private def clean(targetDir: File, streams: TaskStreams): Seq[File] = {
    val log = streams.log

    log.info("Cleaning dialog schema")

    IO.delete(targetDir)

    Seq(targetDir)
  }

  private def generate(srcDir: File, targetDir: File, classpath: Classpath, javaHome: Option[File], streams: TaskStreams): Seq[File] = {
    val log = streams.log

    log.info(f"Generating dialog schema for $srcDir%s")

    val input = srcDir / "dialog-api"

    if (!input.exists()) {
      log.info(f"$input%s does not exists")
      Nil
    } else {
      val output = compiledFileDir(targetDir)

      val cached = FileFunction.cached(streams.cacheDirectory / "dialog-api", FilesInfo.lastModified, FilesInfo.exists) {
        (in: Set[File]) ⇒
          {
            if (!output.exists())
              IO.createDirectory(output)

            val src = input / "dialog.json"
            if (src.exists()) {
              val sources = (new Json2Tree(IO.read(src))).convert()

              sources foreach {
                case (name, source) ⇒
                  val targetFile = compiledFile(targetDir, name)

                  log.info(f"Generated dialogApi $targetFile%s")

                  IO.write(targetFile, source)
              }
            } else {
              log.info(f"no dialog.json file in $input%s")
            }

            (output ** ("*.scala")).get.toSet
          }
      }
      cached((input ** "dialog.json").get.toSet).toSeq
    }
  }
}
