package im.dlg.api

import com.google.protobuf.WireFormat
import im.dlg.api.Types.{ AttributeType, NamedAttributeType }
import scala.language.postfixOps
import treehugger.forest._, definitions._
import treehuggerDSL._

private[api] trait DeserializationTrees extends TreeHelpers with Hacks {
  protected val ErrorType = valueCache("String")
  private def notPresent(name: String) = LIT(name) INT_+ LIT(" is required")
  private def partialName(name: String) = s"partial${name.capitalize}"

  protected val parseExceptionDef: Tree = CLASSDEF("ParseException") withParents REF("Exception") withParams PARAM("partialMessage", valueCache("Any"))

  private def partialTypeDef(name: String, attributes: Vector[Attribute]): Option[Vector[Tree]] = {
    val (params, forExprs) = attributes.sortBy(_.id).foldLeft[(Vector[ValDef], Vector[ForValFrom])]((Vector.empty, Vector.empty)) {
      case ((params, forExprs), attr) ⇒

        attr.typ match {
          case typ @ Types.List(listType @ (Types.Struct(_) | Types.Enum(_))) ⇒
            (
              params :+ PARAM(partialName(attr.name), attrType(attr.typ)).tree,
              forExprs :+ (VALFROM(attr.name) := RIGHT(REF(partialName(attr.name))) DOT "right")
            )
          case typ @ Types.List(_) ⇒
            (
              params :+ PARAM(partialName(attr.name), attrType(attr.typ)).tree,
              forExprs
            )
          case typ @ (Types.Struct(_) | Types.Trait(_)) ⇒
            (
              params :+ PARAM(partialName(attr.name), optionType(attrType(typ))).tree,
              forExprs :+ (VALFROM(attr.name) := REF(partialName(attr.name)) DOT "toRight" APPLY notPresent(attr.name) DOT "right")
            )
          case typ @ Types.Opt(Types.Struct(_) | Types.Trait(_)) ⇒
            (
              params :+ PARAM(partialName(attr.name), attrType(typ)).tree,
              forExprs :+ (
                VALFROM(attr.name) := RIGHT(REF(partialName(attr.name))) DOT "right"
              )
            )
          case typ @ Types.Opt(optType) ⇒
            (
              params :+ PARAM(partialName(attr.name), attrType(attr.typ)).tree,
              forExprs :+ (VALFROM(attr.name) := RIGHT(REF(partialName(attr.name))) DOT "right")
            )
          case typ ⇒
            (
              params :+ PARAM(partialName(attr.name), optionType(attrType(attr.typ))).tree,
              forExprs :+ (VALFROM(attr.name) := REF(partialName(attr.name)) DOT "toRight" APPLY notPresent(attr.name) DOT "right")
            )
        }
    }

    val className = "Partial"

    if (params.nonEmpty) {
      val completeBuilder = valueCache(name) APPLY (attributes.sortBy(_.id) map { attr ⇒
        attr.typ match {
          case Types.List(_) ⇒ REF(partialName(attr.name))
          case _             ⇒ REF(attr.name)
        }
      })

      val asCompleteTree = if (forExprs.isEmpty) { // there are lists only
        RIGHT(completeBuilder)
      } else {
        BLOCK(FOR(forExprs) YIELD completeBuilder)
      }

      val classDef = CASECLASSDEF(className) withFlags Flags.PRIVATE withParams params := BLOCK(
        DEF("asComplete", eitherType(ErrorType, name)) := asCompleteTree
      )

      def emptyValue(attr: Attribute) = attr.typ match {
        case Types.List(Types.Struct(_) | Types.Trait(_)) ⇒ EmptyVector
        case Types.List(_) ⇒ EmptyVector
        case Types.Opt(_) ⇒ NONE
        case _ ⇒ NONE
      }

      val objDef = OBJECTDEF(className) withFlags (Flags.PRIVATE) := BLOCK(
        VAL("empty") := REF(className) DOT "apply" APPLY (attributes.sortBy(_.id) map (attr ⇒ emptyValue(attr)))
      )

      Some(Vector(classDef, objDef))
    } else {
      None
    }
  }

  protected def traitDeserializationTrees(traitName: String, children: Vector[NamedItem]): Vector[Tree] = {
    val attributes = Vector(
      Attribute(Types.Int32, 1, "header"),
      Attribute(Types.Bytes, 2, "body")
    )

    val parseUnitName = s"Parsed$traitName"

    val typeDef = CASECLASSDEF(parseUnitName) withParams (PARAM("header", IntClass), PARAM("body", arrayType(ByteClass)))
    val pTypeDef = partialTypeDef(parseUnitName, attributes).get

    val parseFromDef: Tree = {
      val cases = fieldCases(attributes) ++ defaultFieldCases

      val partialTypeRef = valueCache("Partial")

      DEF("parseFrom", eitherType(ErrorType, valueCache(traitName))) withParams PARAM("in", valueCache("CodedInputStream")) := BLOCK(
        DEF("doParse", eitherType(ErrorType, partialTypeRef))
          withAnnots ANNOT("annotation.tailrec")
          withParams PARAM("partialMessage", partialTypeRef) := BLOCK(
            (REF("in") DOT "readTag()") MATCH cases
          ),

        REF("doParse") APPLY (partialTypeRef DOT "empty") DOT "right" FLATMAP (WILDCARD DOT "asComplete" MATCH (
          CASE(REF("Left") APPLY REF("x")) ==> (REF("Left") APPLY REF("x")),
          if (children.nonEmpty) {
            CASE(REF("Right") APPLY (REF(parseUnitName) APPLY (REF("header"), REF("bodyBytes")))) ==> (
              REF("header") MATCH (children map (c ⇒ (c, c.traitExt)) map {
                case (child, Some(TraitExt(_, key))) ⇒
                  CASE(LIT(key)) ==>
                    BLOCK(
                      VAL("stream") := valueCache("CodedInputStream") DOT "newInstance" APPLY REF("bodyBytes"),
                      REF("Refs") DOT child.name DOT "parseFrom" APPLY REF("stream")
                    )
                case _ ⇒
                  throw new Exception("No trait key in trait child")
              })
            )
          } else {
            CASE(REF("_")) ==> THROW(NEW(REF("ParseException") APPLY LIT("Not implemented")))
          }
        ))
      )
    }

    (pTypeDef ++ Vector(typeDef.tree) :+ parseFromDef) ++ helperParseFromDefs(traitName)
  }

  protected def deserializationTrees(name: String, attributes: Vector[Attribute]): Vector[Tree] = {
    val optPartialTypeDef = partialTypeDef(name, attributes)

    val parseDef =
      optPartialTypeDef match {
        case Some(_) ⇒
          val cases = fieldCases(attributes) ++ defaultFieldCases

          val partialTypeRef = valueCache("Partial")
          val parseDefType = eitherType(ErrorType, valueCache(name))

          DEF("parseFrom", parseDefType) withParams PARAM("in", valueCache("CodedInputStream")) := BLOCK(
            DEF("doParse", eitherType(ErrorType, partialTypeRef))
              withParams PARAM("partialMessage", partialTypeRef) := BLOCK(
                (REF("in") DOT "readTag()") MATCH cases
              ),

            TRY(
              REF("doParse") APPLY (partialTypeRef DOT "empty") DOT "right" FLATMAP (WILDCARD DOT "asComplete")
            ) CATCH (CASE(REF("e: Exception")) ==> LEFT(REF("e") DOT "getMessage")) ENDTRY
          )
        case None ⇒
          val cases = Vector(
            CASE(LIT(0)) ==> BLOCK(),
            CASE(REF("default")) ==>
              (
                IF(REF("in") DOT "skipField" APPLY REF("default") ANY_== LIT(true)) THEN (
                  REF("doParse") APPLY ()
                ) ELSE BLOCK()
              )
          )

          val parseDefType = eitherType(ErrorType, valueCache(name))

          DEF("parseFrom", parseDefType) withParams PARAM("in", valueCache("CodedInputStream")) := BLOCK(
            DEF("doParse", valueCache("Unit"))
              withParams () := BLOCK(
                (REF("in") DOT "readTag()") MATCH cases
              ),

            REF("doParse") APPLY (),

            REF("Right") APPLY REF(name)
          )
      }

    optPartialTypeDef match {
      case Some(trees) ⇒
        (trees :+ parseDef) ++ helperParseFromDefs(name)
      case None ⇒
        parseDef +: helperParseFromDefs(name)
    }
  }

  private def helperParseFromDefs(resultType: String): Vector[Tree] = Vector(
    DEF("parseFrom", eitherType(ErrorType, valueCache(resultType))) withParams PARAM("bytes", arrayType(ByteClass)) :=
      REF("parseFrom") APPLY (REF("CodedInputStream") DOT "newInstance" APPLY REF("bytes")),

    DEF("parseFrom", eitherType(ErrorType, valueCache(resultType))) withParams PARAM("bytes", valueCache("ByteString")) :=
      REF("parseFrom") APPLY (REF("CodedInputStream") DOT "newInstance" APPLY (REF("bytes") DOT "asReadOnlyByteBuffer"))
  )

  private def simpleReader(fn: String) = REF("in") DOT fn APPLY ()

  private def reader(typ: Types.AttributeType): Tree = typ match {
    case Types.Int32            ⇒ simpleReader("readInt32")
    case Types.Int64            ⇒ simpleReader("readInt64")
    case Types.Bool             ⇒ simpleReader("readBool")
    case Types.Double           ⇒ simpleReader("readDouble")
    case Types.String           ⇒ simpleReader("readString")
    case Types.Bytes            ⇒ simpleReader("readByteArray")
    case Types.Opt(optAttrType) ⇒ reader(optAttrType)
    case Types.List(typ @ (Types.Struct(_) | Types.Trait(_))) ⇒
      val structName = typ.asInstanceOf[NamedAttributeType].name

      BLOCK(
        VAL("length") := REF("in") DOT "readRawVarint32" APPLY (),
        VAL("oldLimit") := REF("in") DOT "pushLimit" APPLY REF("length"),

        VAL("res") := REF("Refs") DOT structName DOT "parseFrom" APPLY REF("in"),

        REF("in") DOT "checkLastTagWas" APPLY LIT(0),
        REF("in") DOT "popLimit" APPLY REF("oldLimit"),

        REF("res")
      )
    case Types.List(Types.String) ⇒ reader(Types.String)
    case Types.List(Types.Bytes)  ⇒ reader(Types.Bytes)
    case Types.List(listAttrType) ⇒
      BLOCK(
        VAL("length") := REF("in") DOT "readRawVarint32" APPLY (),
        VAL("limit") := REF("in") DOT "pushLimit" APPLY REF("length"),

        VAL("values") := REF("Iterator").DOT("continually").APPLY(
          reader(listAttrType)
        ).DOT("takeWhile").APPLY(LAMBDA(PARAM(WILDCARD)) ==>
            (REF("in") DOT "getBytesUntilLimit" APPLY ()) INT_> LIT(0)).DOT("toVector"),

        REF("in") DOT "popLimit" APPLY REF("limit"),

        REF("values")
      )
    case struct @ Types.Struct(_) ⇒
      val structName = struct.name
      BLOCK(
        VAL("length") := REF("in") DOT "readRawVarint32" APPLY (),
        VAL("oldLimit") := REF("in") DOT "pushLimit" APPLY REF("length"),

        VAL("message") := REF("Refs") DOT structName DOT "parseFrom" APPLY REF("in"),

        REF("in") DOT "checkLastTagWas" APPLY LIT(0),
        REF("in") DOT "popLimit" APPLY REF("oldLimit"),

        REF("message")
      )
    case enum @ Types.Enum(_) ⇒
      val enumName = enum.name

      BLOCK(
        REF("Refs") DOT enumName APPLY (
          REF("in") DOT "readEnum" APPLY ()
        )
      )
    case trai @ Types.Trait(_) ⇒
      val traitName = trai.name

      BLOCK(
        VAL("bytes") := REF("in") DOT "readByteArray" APPLY (),
        VAL("stream") := valueCache("CodedInputStream") DOT "newInstance" APPLY REF("bytes"),
        REF("Refs") DOT traitName DOT "parseFrom" APPLY REF("stream")
      )
    case alias @ Types.Alias(aliasName) ⇒
      reader(aliasesPrim(aliasName))
    case attr ⇒
      throw new RuntimeException(s"Unknown attr type: ${attr}")
  }

  private def doParseWithCopy(attrName: String, attrType: Types.AttributeType, appendOp: Option[String]): Tree = {
    REF("doParse") APPLY (
      REF("partialMessage") DOT "copy" APPLY (
        appendOp match {
          case Some(op) ⇒
            REF(partialName(attrName)) :=
              REF("partialMessage") DOT partialName(attrName) INFIX op APPLY REF("value")
          case None ⇒
            REF(partialName(attrName)) := SOME(REF("value"))
        }
      )
    )
  }

  private def readCaseBody(attrName: String, attrType: Types.AttributeType, appendOp: Option[String]): Tree =
    attrType match {
      case Types.Struct(_) | Types.Trait(_) | Types.List(Types.Struct(_) | Types.Trait(_)) | Types.Opt(Types.Struct(_) | Types.Trait(_)) ⇒
        reader(attrType) MATCH (
          CASE(RIGHT(REF("value"))) ==> doParseWithCopy(attrName, attrType, appendOp),
          CASE(LEFT(REF("e"))) ==> LEFT(REF("e"))
        )
      case _ ⇒ BLOCK(
        VAL("value") := reader(attrType),
        doParseWithCopy(attrName, attrType, appendOp)
      )
    }

  @annotation.tailrec
  private def wireType(attrType: Types.AttributeType): Int = {
    attrType match {
      case Types.Int32 | Types.Int64 | Types.Bool ⇒ WireFormat.WIRETYPE_VARINT
      case Types.Double ⇒ WireFormat.WIRETYPE_FIXED64
      case Types.Enum(_) ⇒ WireFormat.WIRETYPE_VARINT
      case Types.String | Types.Bytes | Types.Struct(_) | Types.Trait(_) ⇒ WireFormat.WIRETYPE_LENGTH_DELIMITED
      case Types.Opt(optAttrType) ⇒ wireType(optAttrType)
      case Types.List(listAttrType) ⇒ wireType(listAttrType)
      case Types.Alias(name) ⇒ wireType(aliasesPrim.get(name).get)
      case unsupported ⇒ throw new Exception(f"Unsupported wire type: $unsupported%s")
    }
  }

  private def fieldCases(attributes: Vector[Attribute]): Vector[CaseDef] = {
    val cases = attributes map { attr ⇒
      val baseCaseExpr = CASE(LIT((attr.id << 3) | wireType(attr.typ)))

      attr.typ match {
        case typ @ Types.List(listAttrType) ⇒
          val exact = dealias(typ)

          if (exact.isInstanceOf[Types.Struct] ||
            exact.isInstanceOf[Types.Trait] ||
            exact.isInstanceOf[Types.Bytes.type] ||
            exact.isInstanceOf[Types.String.type]) {
            val baseCase = baseCaseExpr ==> BLOCK(
              readCaseBody(attr.name, typ, Some(":+"))
            )

            Vector(baseCase)
          } else {
            val baseCase = baseCaseExpr ==> BLOCK(
              readCaseBody(attr.name, listAttrType, Some(":+"))
            )

            val listCase = CASE(LIT((attr.id << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED)) ==> BLOCK(
              readCaseBody(attr.name, attr.typ, Some("++"))
            )

            Vector(baseCase, listCase)
          }

        case attrType ⇒
          Vector(baseCaseExpr ==> BLOCK(
            readCaseBody(attr.name, attr.typ, None)
          ))
      }
    }

    cases.flatten
  }

  private val defaultFieldCases: Vector[CaseDef] = {
    Vector(
      CASE(LIT(0)) ==> RIGHT(REF("partialMessage")),
      CASE(REF("default")) ==>
        (
          IF(REF("in") DOT "skipField" APPLY REF("default") ANY_== LIT(true)) THEN (
            REF("doParse") APPLY REF("partialMessage")
          ) ELSE RIGHT(REF("partialMessage"))
        )
    )
  }
}
