package im.dlg.api

import im.dlg.api.Categories.Danger
import treehugger.forest._, definitions._
import treehuggerDSL._

import scala.language.postfixOps

trait StringHelperTrees extends TreeHelpers with Hacks {

  private val DANGER_NOTE = DocTag.Note("Contains sensitive data!!!")

  protected def generateDoc(doc: Doc): Vector[DocElement] = {
    val attrDocs = doc.attributeDocs map { aDoc ⇒
      val nameDesc = List(hackAttributeName(aDoc.argument), aDoc.description)
      val args = if (aDoc.category == Danger) nameDesc :+ DANGER_NOTE else nameDesc
      DocTag.Param(args: _*)
    }
    DocText(doc.generalDoc) +: attrDocs
  }

  protected val stringHelpersTree: Tree = OBJECTDEF("StringHelpers") withFlags PRIVATEWITHIN("api") := BLOCK(
    DEF("escapeSpecial", StringClass).withParams(PARAM("source", StringClass)) :=
      REF("source") DOT "flatMap" APPLY BLOCK(
        CASE(
          ID("c"),
          IF((REF("c") ANY_== LIT('\n')) OR (REF("c") ANY_== LIT('\r')) OR (REF("c") ANY_== LIT('\t')) OR (REF("c") ANY_== LIT('\f')))
        ) ==> INTERP("s", LIT("\\"), REF("c")),
        CASE(
          ID("c"),
          IF(REF("c") ANY_== LIT('"'))
        ) ==> LIT("\\\""),
        CASE(ID("c")) ==> REF("c") TOSTRING
      )
  )

  protected def generateToString(name: String, attributes: Vector[Attribute], attribureDocs: Vector[AttributeDoc]): DefDef = {
    DEF("toString", StringClass) withFlags Flags.OVERRIDE := IF(REF("Debug") DOT "isDebug") THEN
      INFIX_CHAIN("+", debug(name, attributes)) ELSE INFIX_CHAIN("+", prod(name, attributes, attribureDocs))
  }

  private def prod(name: String, attributes: Vector[Attribute], attribureDocs: Vector[AttributeDoc]) = {
    val docMapped = attribureDocs.map(e ⇒ e.argument → e).toMap
    val body = className(name) +: (attributes foldLeft Vector.empty[Vector[Tree]]) { (acc, elem) ⇒
      val show = docMapped.get(elem.name) map (ad ⇒ Categories.Category.isVisible(ad.category)) getOrElse true
      if (show) acc :+ ((wrapQoutes(LIT(elem.name)) :+ LIT(": ")) ++ wrapAttr(elem.typ, elem.name)) else acc
    }
    jsonTree(flattenTree(body))
  }

  private def debug(name: String, attributes: Vector[Attribute]) = {
    val body = className(name) +: (attributes foldLeft Vector.empty[Vector[Tree]]) { (acc, elem) ⇒
      acc :+ ((wrapQoutes(LIT(elem.name)) :+ LIT(": ")) ++ wrapAttr(elem.typ, elem.name))
    }
    jsonTree(flattenTree(body))
  }

  private def jsonTree(body: Vector[Tree]): Vector[Tree] = (LIT("{ ") +: body) :+ LIT(" }")

  private def flattenTree(elements: Vector[Vector[Tree]]): Vector[Tree] = elements match {
    case Vector() ⇒ Vector.empty[Tree]
    case v        ⇒ v reduce ((a, b) ⇒ (a :+ LIT(",")) ++ b)
  }

  private def className(name: String): Vector[Tree] =
    (wrapQoutes(LIT("_name")) :+ LIT(": ")) ++ wrapQoutes(LIT(name))

  private def wrapQoutes(e: Tree): Vector[Tree] = Vector(LIT("\""), e, LIT("\""))

  private def wrapAttr(attrType: Types.AttributeType, attrName: String): Vector[Tree] = {
    attrType match {
      case Types.Int32 | Types.Int64 | Types.Bool | Types.Double | Types.Bytes ⇒ Vector(REF(attrName))
      case Types.String ⇒ wrapQoutes(REF("StringHelpers") DOT "escapeSpecial" APPLY REF(attrName))
      case Types.Enum(_) ⇒ Vector(REF(attrName) DOT "id")
      case Types.Opt(typ) ⇒
        val optMapToString = REF(attrName) MAP LAMBDA(PARAM("e")) ==> INFIX_CHAIN("+", wrapAttr(typ, "e"))
        Vector(optMapToString DOT "getOrElse" APPLY NULL)
      case Types.List(typ) ⇒
        val listMapToString =
          REF(attrName) MAP LAMBDA(PARAM("e")) ==> INFIX_CHAIN("+", wrapAttr(typ, "e")) DOT "mkString" APPLY LIT(", ")
        Vector(LIT("[ "), listMapToString, LIT(" ]"))
      case Types.Struct(_) | Types.Trait(_) ⇒ Vector(REF(attrName) TOSTRING)
      case Types.Alias(aliasName)           ⇒ wrapAttr(aliasesPrim.get(aliasName).get, attrName)
      case _                                ⇒ Vector(REF(attrName))
    }
  }

}
