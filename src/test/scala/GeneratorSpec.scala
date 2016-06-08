package im.dlg.api

import org.specs2._

class GeneratorSpec extends Specification {
  def is = s2"""
  Generator sould generate valid scala code $e1
  """

  def e1 = {

    val genSource = (new Json2Tree(jsonSchema)).convert
    // TODO: write some specs
    success
  }

  private lazy val jsonSchema = scala.io.Source.fromURL(getClass.getResource("/actor.json")).getLines.mkString("\n")
  private lazy val expectedSource = scala.io.Source.fromURL(getClass.getResource("/expected.scala")).getLines.mkString("\n")
}
