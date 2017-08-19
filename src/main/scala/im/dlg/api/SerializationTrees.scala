package im.dlg.api

import scala.language.postfixOps
import treehugger.forest._, definitions._
import treehuggerDSL._

private[api] trait SerializationTrees extends TreeHelpers with StringHelperTrees {
  private def CodedOutputStreamClass = valueCache("CodedOutputStream")
  private def CodedOutputStream = REF(CodedOutputStreamClass)

  private def simpleWriter(writeFn: String, attrId: Int, attrName: String): Tree =
    simpleWriter(writeFn, attrId, REF(attrName))

  private def simpleWriter(writeFn: String, attrId: Int, attrValue: Tree): Tree = {
    REF("out") DOT (writeFn) APPLY (LIT(attrId), attrValue)
  }

  private def writer(id: Int, name: String, typ: Types.AttributeType): Vector[Tree] = {
    typ match {
      case Types.Int32  ⇒ Vector(simpleWriter("writeInt32", id, name))
      case Types.Int64  ⇒ Vector(simpleWriter("writeInt64", id, name))
      case Types.Bool   ⇒ Vector(simpleWriter("writeBool", id, name))
      case Types.Double ⇒ Vector(simpleWriter("writeDouble", id, name))
      case Types.String ⇒ Vector(simpleWriter("writeString", id, name))
      case Types.Bytes  ⇒ Vector(simpleWriter("writeByteArray", id, name))
      case Types.UUID ⇒ Vector(
        VAL(s"raw$name") := REF("UUIDUtils") DOT "longsToBytes" APPLY (REF(s"$name.getMostSignificantBits"), REF(s"$name.getLeastSignificantBits")),
        simpleWriter("writeByteArray", id, s"raw$name")
      )
      case Types.Enum(_) ⇒
        Vector(simpleWriter("writeEnum", id, REF(name) DOT ("id")))
      case Types.Opt(optAttrType) ⇒
        Vector(
          REF(name) FOREACH LAMBDA(PARAM("x")) ==> BLOCK(
            writer(id, "x", optAttrType)
          )
        )
      case Types.List(listAttrType) ⇒
        Vector(
          REF(name) FOREACH LAMBDA(PARAM("x")) ==> BLOCK(
            writer(id, "x", listAttrType)
          )
        )
      case struct @ Types.Struct(structName) ⇒
        Vector(
          REF("out") DOT ("writeTag") APPLY (LIT(id), REF("com.google.protobuf.WireFormat.WIRETYPE_LENGTH_DELIMITED")),
          REF("out") DOT ("writeUInt32NoTag") APPLY (REF(name) DOT (if (isChild(struct.name)) "childGetSerializedSize" else "getSerializedSize")),
          REF(name) DOT (if (isChild(struct.name)) "childWriteTo" else "writeTo") APPLY (REF("out"))
        )
      case Types.Trait(traitName) ⇒
        val traitBaos = f"baos$traitName%s"
        val traitOut = f"out$traitName%s"

        Vector(
          VAL(traitBaos) := NEW(REF("java.io.ByteArrayOutputStream")),
          VAL(traitOut) := CodedOutputStreamClass DOT ("newInstance") APPLY (REF(traitBaos)),
          REF(name) DOT ("writeTo") APPLY (REF(traitOut)),
          REF(traitOut) DOT ("flush") APPLY (),
          simpleWriter(
            "writeByteArray",
            id,
            REF(traitBaos) DOT ("toByteArray")
          ),
          REF(traitBaos) DOT ("close") APPLY ()
        )
      case a @ Types.Alias(aliasName) ⇒
        writer(id, name, aliasesPrim.get(aliasName).get)
    }
  }

  private def simpleComputer(computeFn: String, attrId: Int, attrName: String): Tree =
    simpleComputer(computeFn, attrId, REF(attrName))

  private def simpleComputer(computeFn: String, attrId: Int, attrValue: Tree): Tree = {
    CodedOutputStream DOT (computeFn) APPLY (LIT(attrId), attrValue)
  }

  private def computer(id: Int, name: String, typ: Types.AttributeType): Vector[Tree] = {
    typ match {
      case Types.Int32   ⇒ Vector(simpleComputer("computeInt32Size", id, name))
      case Types.Int64   ⇒ Vector(simpleComputer("computeInt64Size", id, name))
      case Types.Bool    ⇒ Vector(simpleComputer("computeBoolSize", id, name))
      case Types.Double  ⇒ Vector(simpleComputer("computeDoubleSize", id, name))
      case Types.String  ⇒ Vector(simpleComputer("computeStringSize", id, name))
      case Types.Bytes   ⇒ Vector(simpleComputer("computeByteArraySize", id, REF(name)))
      case Types.Enum(_) ⇒ Vector(simpleComputer("computeEnumSize", id, REF(name) DOT ("id")))
      case Types.UUID ⇒ Vector(
        VAL(s"raw$name") := REF("UUIDUtils") DOT "longsToBytes" APPLY (REF(s"$name.getMostSignificantBits"), REF(s"$name.getLeastSignificantBits")),
        simpleComputer("computeByteArraySize", id, REF(s"raw$name"))
      )
      case Types.Opt(optAttrType) ⇒
        Vector(
          IF(REF(name) DOT "isDefined") THEN (
            BLOCK(
              (VAL("__value") := REF(name) DOT "get") +:
                computer(id, "__value", optAttrType)
            )
          ) ELSE LIT(0)
        )
      case Types.List(listAttrType) ⇒
        // TODO: optimize using view
        Vector(
          PAREN(REF(name) MAP LAMBDA(PARAM("x")) ==> BLOCK(
            computer(id, "x", listAttrType)
          )) DOT ("foldLeft") APPLY (LIT(0)) APPLY (WILDCARD INT_+ WILDCARD)
        )
      case t @ (Types.Struct(_) | Types.Trait(_)) ⇒
        val serSize = t match {
          case struct: Types.Struct if isChild(struct.name) ⇒ "childGetSerializedSize"
          case _ ⇒ "getSerializedSize"
        }

        Vector(
          VAL("size") := REF(name) DOT (serSize),
          (CodedOutputStreamClass DOT ("computeTagSize") APPLY (LIT(id))) INT_+
            (CodedOutputStreamClass DOT ("computeUInt32SizeNoTag") APPLY (REF("size"))) INT_+
            REF("size")
        )
      case Types.Alias(aliasName) ⇒
        computer(id, name, aliasesPrim.get(aliasName).get)
    }
  }

  protected def serializationTrees(packageName: String, name: String, attributes: Vector[Attribute], doc: Doc): Vector[Tree] = {
    val (writer, size) = writerAndSize(attributes)

    Vector(
      DEF("writeTo", UnitClass) withParams (PARAM("out", CodedOutputStreamClass)) := writer,
      LAZYVAL("getSerializedSize", IntClass) := size,
      DEF("toByteArray", arrayType(ByteClass)) := BLOCK(
        VAL("res") := NEW(arrayType(ByteClass), REF("getSerializedSize")),
        VAL("out") := CodedOutputStreamClass DOT ("newInstance") APPLY (REF("res")),
        REF("writeTo") APPLY (REF("out")),
        REF("out") DOT ("checkNoSpaceLeft") APPLY (),
        REF("res")
      ),
      generateToString(name, attributes, doc.attributeDocs)
    )
  }

  protected def traitChildSerializationTrees(packageName: String, name: String, attributes: Vector[Attribute], doc: Doc): Vector[Tree] = {
    val (writer, size) = writerAndSize(attributes)

    Vector(
      DEF("childWriteTo", UnitClass) withParams (PARAM("out", CodedOutputStreamClass)) := writer,
      LAZYVAL("childGetSerializedSize", IntClass) := size,
      DEF("childToByteArray", arrayType(ByteClass)) := BLOCK(
        VAL("res") := NEW(arrayType(ByteClass), REF("childGetSerializedSize")),
        VAL("out") := CodedOutputStreamClass DOT ("newInstance") APPLY (REF("res")),
        REF("childWriteTo") APPLY (REF("out")),
        REF("out") DOT ("checkNoSpaceLeft") APPLY (),
        REF("res")
      ),
      generateToString(name, attributes, doc.attributeDocs)
    )
  }

  protected def traitSerializationTrees(traitName: String, children: Vector[NamedItem]): Vector[Tree] = {
    Vector(
      DEF("writeTo", UnitClass) withParams (PARAM("out", CodedOutputStreamClass)) withFlags (Flags.FINAL) := BLOCK(
        REF("out") DOT ("writeInt32") APPLY (LIT(1), REF("header")),
        REF("out") DOT ("writeByteArray") APPLY (LIT(2), REF("childToByteArray"))
      ),
      LAZYVAL("getSerializedSize", IntClass) withFlags (Flags.FINAL) := BLOCK(
        VAL("headerSizeWithTag") := CodedOutputStream DOT ("computeInt32Size") APPLY (LIT(1), REF("header")),
        VAL("childTagSize") := CodedOutputStream DOT ("computeTagSize") APPLY (LIT(2)),
        VAL("childSerializedSize") := REF("childGetSerializedSize"),
        VAL("childSizeWithoutTag") := INFIX_CHAIN("+", Seq(
          CodedOutputStream DOT ("computeUInt32SizeNoTag") APPLY (REF("childSerializedSize")),
          REF("childSerializedSize")
        )),
        INFIX_CHAIN("+", Seq(REF("headerSizeWithTag"), REF("childTagSize"), REF("childSizeWithoutTag")))
      ),
      DEF("toByteArray", arrayType(ByteClass)) withFlags (Flags.FINAL) := BLOCK(
        VAL("res") := NEW(arrayType(ByteClass), REF("getSerializedSize")),
        VAL("out") := CodedOutputStreamClass DOT ("newInstance") APPLY (REF("res")),
        REF("writeTo") APPLY (REF("out")),
        REF("out") DOT ("checkNoSpaceLeft") APPLY (),
        REF("res")
      ),
      DEF("childWriteTo", UnitClass) withParams (PARAM("out", CodedOutputStreamClass)),
      DEF("childGetSerializedSize", IntClass),
      DEF("childToByteArray", arrayType(ByteClass))
    )
  }

  private def writerAndSize(attributes: Vector[Attribute]) = {
    val sortedAttributes = attributes.sortBy(_.id)

    val writers: Vector[Tree] = sortedAttributes map { attr ⇒
      writer(attr.id, attr.name, attr.typ)
    } flatten

    val sizeComputers: Map[String, Tree] =
      (if (sortedAttributes.length > 0) {
        sortedAttributes map { attr ⇒
          (s"__${attr.name}Size" → BLOCK(computer(attr.id, attr.name, attr.typ)))
        }
      } else Vector.empty).toMap

    val size = BLOCK((sizeComputers map {
      case (name, computer) ⇒
        VAL(name) := computer
    }).toVector :+
      (if (sizeComputers.isEmpty) LIT(0)
      else INFIX_CHAIN("+", sizeComputers.keys map (REF(_)))))

    (BLOCK(writers), size)
  }
}
