package im.dlg.api

import im.dlg.api.Types.AttributeType
import treehugger.forest._, definitions._
import treehuggerDSL._
import scala.language.postfixOps
import spray.json._

final class Json2Tree(jsonString: String)
    extends JsonFormats
    with JsonHelpers
    with SerializationTrees
    with DeserializationTrees
    with CodecTrees
    with ApiServiceTrees
    with DebugHelpersTrees {
  private val jsonAst = jsonString.parseJson
  private val rootObj = jsonAst.convertTo[JsObject]

  override val aliases: Map[String, String] = rootObj.withField("aliases") {
    case JsArray(jsAliases) ⇒
      jsAliases.map { jsAlias ⇒
        val alias = aliasFormat.read(jsAlias)
        (normalizeAlias(alias.alias), alias.typ)
      }.toMap
    case _ ⇒ deserializationError("Aliases should be JsArray")
  }

  override val aliasesPrim: Map[String, AttributeType] = aliases mapValues primitiveType

  def convert(): Map[String, String] = {
    val packages: Vector[(String, String, Vector[Item])] = rootObj.fields("sections").convertTo[JsArray].elements map {
      case obj: JsObject ⇒
        (obj.fields("package"), obj.getFields("doc")) match {
          case (JsString(packageName), packageDocs) ⇒
            val docString = packageDocs match {
              case Seq(JsArray(docs)) ⇒
                docs flatMap {
                  case JsString(content) ⇒ Some(content)
                  case _                 ⇒ None
                } mkString "\n"
              case _ ⇒ ""
            }
            val packageItems = items(obj.fields("items").convertTo[JsArray].elements)

            packageItems foreach {
              case ni: NamedItem ⇒
                typeMapping += ni.name → ni
            }

            (packageName, docString, packageItems)
          case _ ⇒
            throw new Exception("package field is not a JsString")
        }
      case _ ⇒
        throw new Exception("section is not a JsObject")
    }

    val refsTreesSeq: Vector[(Vector[Tree], (String, Tree))] = packages map {
      case (packageName, packageDoc, items) ⇒
        val (globalRefs, block) = itemsBlock(packageName, items)
        (globalRefs, packageName → ((PACKAGE(packageName) := block) withDoc List(packageDoc)))
    }

    val (globalRefsV, packageTrees) = refsTreesSeq.unzip

    val globalRefsTree: Tree = OBJECTDEF("Refs") withFlags PRIVATEWITHIN("api") := BLOCK(globalRefsV.flatten)

    val uuidUtils: Tree = OBJECTDEF("UUIDUtils") := BLOCK(
      DEF("bytesToUUID", valueCache("java.util.UUID")) withParams (PARAM("bytes", valueCache("Array[Byte]"))) := BLOCK(
        REF(
          """
            |  if (bytes.length == 16) {
            |    val arrays = bytes.grouped(8).toList
            |    new java.util.UUID(longFromBytes(arrays(0)), longFromBytes(arrays(1)))
            |  } else {
            |    new java.util.UUID(0L, 0L)
            |  }
          """.stripMargin
        )
      ),

      DEF("longFromBytes", LongClass) withParams (PARAM("bytes", valueCache("Array[Byte]"))) := BLOCK(
        REF(
          """
            |    var value = 0L
            |    var i = 0
            |
            |    while (i < 8) {
            |      value = (value << 8) + (bytes(i) & 0xff)
            |      i += 1
            |    }
            |
            |    value
          """.stripMargin
        )
      ),

      DEF("longsToBytes", valueCache("Array[Byte]")) withParams (PARAM("v1", LongClass), PARAM("v2", LongClass)) := BLOCK(
        REF(
          """
            |    val bytes = Array.ofDim[Byte](16)
            |    var i = 15
            |    var j = 1
            |    var v = v2
            |
            |    while (j > -1) {
            |      if (j == 0) v = v1
            |
            |      while (i >= j * 8) {
            |        bytes(i) = (v & 0xFF).toByte
            |        v >>= 8
            |        i -= 1
            |      }
            |
            |      j -= 1
            |    }
            |
            |    bytes
          """.stripMargin
        )
      )
    )

    val bserializableDef: Tree = TRAITDEF("BSerializable") withParents valueCache("java.io.Serializable") := BLOCK(
      DEF("toByteArray", arrayType(ByteClass)),
      DEF("getSerializedSize", IntClass)
    )

    val containsHeaderDef: Tree = TRAITDEF("ContainsHeader") := BLOCK(
      VAL("header", IntClass)
    )

    val updateBoxDef: Tree = TRAITDEF("UpdateBox") withParents (valueCache("BSerializable"), valueCache("ContainsHeader"))

    val updateDef: Tree = TRAITDEF("Update") withParents (valueCache("BSerializable"), valueCache("ContainsHeader")) := BLOCK(
      VAL("_relatedUserIds", valueCache("IndexedSeq[Int]")): Tree,
      VAL("_relatedGroupIds", valueCache("IndexedSeq[Int]")): Tree
    )
    val requestDef: Tree = CASECLASSDEF("Request") withParams PARAM("body", valueCache("RpcRequest"))
    val requestObjDef: Tree = OBJECTDEF("Request") withParents valueCache("ContainsHeader") := BLOCK(
      VAL("header") := LIT(1)
    )

    val rpcResultDef: Tree = TRAITDEF("RpcResult")

    val rpcRequestDef: Tree = TRAITDEF("RpcRequest") withParents valueCache("BSerializable") withParents valueCache("ContainsHeader")
    val rpcResponseDef: Tree = TRAITDEF("RpcResponse") withParents valueCache("BSerializable") withParents valueCache("ContainsHeader")
    val errorDataDef: Tree = TRAITDEF("ErrorData") withParents valueCache("BSerializable")
    val rpcOkDef: Tree = CASECLASSDEF("RpcOk") withParents valueCache("RpcResult") withParams PARAM("response", valueCache("RpcResponse"))
    val rpcErrorDef: Tree = CASECLASSDEF("RpcError") withParents valueCache("RpcResult") withParams (
      PARAM("code", IntClass),
      PARAM("tag", StringClass),
      PARAM("userMessage", StringClass),
      PARAM("canTryAgain", BooleanClass),
      PARAM("data", optionType(valueCache("ErrorData")))
    )
    val rpcInternalErrorDef: Tree = CASECLASSDEF("RpcInternalError") withParents valueCache("RpcResult") withParams (
      PARAM("canTryAgain", BooleanClass),
      PARAM("tryAgainDelay", IntClass)
    )

    val baseTrees: Vector[Tree] = Vector(
      globalRefsTree,
      parseExceptionDef,
      uuidUtils,
      bserializableDef,
      containsHeaderDef,
      updateBoxDef,
      errorDataDef,
      rpcResultDef,
      rpcOkDef,
      rpcErrorDef,
      rpcInternalErrorDef,
      updateDef,
      rpcRequestDef,
      rpcResponseDef,
      requestDef,
      requestObjDef
    ) ++ baseServiceTrees ++ Seq(stringHelpersTree, debugTree)

    (
      (packageTrees map {
        case (p, t) ⇒ (p, Vector(t))
      }) ++ Seq(
        "Base" → baseTrees,
        "Codecs" → Vector(codecTrees(packages map (e ⇒ (e._1, e._3))))
      )
    ).map {
          case (name, trees) ⇒
            (name, prettify(treeToString(withImports(
              name,
              Vector(
                "scala.concurrent._",
                "com.google.protobuf.{ CodedInputStream, CodedOutputStream, ByteString }"
              ), trees
            ))))
        }
      .toMap
  }

  private def withImports(name: String, imports: Vector[String], trees: Vector[Tree]): Tree = {
    if (name == "Base") {
      PACKAGEHEADER("im.dlg.api.rpc").mkTree(
        ((imports map (IMPORT(_): Tree)) ++ trees).toList
      )
    } else {
      PACKAGE("im.dlg.api.rpc") := BLOCK(
        (imports map (IMPORT(_))) ++ trees
      )
    }
  }

  private def items(jsonElements: Vector[JsValue]): Vector[Item] = {
    jsonElements flatMap {
      case obj: JsObject ⇒
        obj.getFields("type", "content") match {
          case Seq(JsString("rpc"), o: JsObject)        ⇒ Some(o.convertTo[RpcContent])
          case Seq(JsString("response"), o: JsObject)   ⇒ Some(o.convertTo[RpcResponseContent])
          case Seq(JsString("update"), o: JsObject)     ⇒ Some(o.convertTo[Update])
          case Seq(JsString("update_box"), o: JsObject) ⇒ Some(o.convertTo[UpdateBox])
          case Seq(JsString("struct"), o: JsObject)     ⇒ Some(o.convertTo[Struct])
          case Seq(JsString("enum"), o: JsObject)       ⇒ Some(o.convertTo[Enum])
          case Seq(JsString("trait"), o: JsObject)      ⇒ Some(o.convertTo[Trait])
          case Seq(JsString("comment"), _)              ⇒ None
          case Seq(JsString("empty"))                   ⇒ None
          case Seq(JsString(typ), _)                    ⇒ throw new Exception(f"Unsupported item: $typ%s")
          case _                                        ⇒ throw new Exception("Item type is not a JsString")
        }
      case _ ⇒ throw new Exception("item is not a JsObject")
    }
  }

  private type TreesTraitsChildren = (Vector[Tree], Vector[Tree], Vector[Trait], Vector[NamedItem])
  private type TreesChildren = (Vector[Tree], Vector[Tree], Vector[NamedItem])

  private def merge(a: TreesTraitsChildren, b: TreesTraitsChildren): TreesTraitsChildren = (
    a._1 ++ b._1,
    a._2 ++ b._2,
    a._3 ++ b._3,
    a._4 ++ b._4
  )

  private def merge(a: TreesTraitsChildren, b: (Vector[Tree], Vector[Tree])): TreesTraitsChildren = (
    a._1 ++ b._1,
    a._2 ++ b._2,
    a._3,
    a._4
  )

  private def merge(a: TreesTraitsChildren, b: Vector[Trait]): TreesTraitsChildren =
    a.copy(_3 = a._3 ++ b)

  private def merge(a: TreesTraitsChildren, b: TreesChildren): TreesTraitsChildren =
    (
      a._1 ++ b._1,
      a._2 ++ b._2,
      a._3,
      a._4 ++ b._3
    )

  private def itemsBlock(packageName: String, items: Vector[Item]): (Vector[Tree], Tree) = {
    val requestTraitTree: Tree = TRAITDEF(f"${packageName.capitalize}%sRpcRequest") withParents valueCache("RpcRequest") withFlags Flags.SEALED

    val (globalRefs, trees, traits, allChildren): TreesTraitsChildren = items.foldLeft[TreesTraitsChildren](
      (Vector.empty, Vector.empty, Vector.empty, Vector.empty)
    ) {
        case (trees, item: Item) ⇒ item match {
          case rpc: RpcContent              ⇒ merge(trees, rpcItemTrees(packageName, rpc))
          case response: RpcResponseContent ⇒ merge(trees, namedResponseItemTrees(packageName, response))
          case update: Update               ⇒ merge(trees, updateItemTrees(packageName, update))
          case updateBox: UpdateBox         ⇒ merge(trees, updateBoxItemTrees(packageName, updateBox))
          case struct: Struct               ⇒ merge(trees, structItemTrees(packageName, struct))
          case enum: Enum                   ⇒ merge(trees, enumItemTrees(packageName, enum))
          case trai: Trait                  ⇒ merge(trees, Vector(trai))
        }
      }

    val (traitGlobalRefsV, traitTreesV): (Vector[Vector[Tree]], Vector[Vector[Tree]]) = (traits map (trai ⇒
      traitItemTrees(packageName, trai, allChildren.filter(_.traitExt.map(_.name) == Some(trai.name))))).unzip

    val serviceTrees = packageApiServiceTrees(packageName, items)

    (globalRefs ++ traitGlobalRefsV.flatten, BLOCK(Vector(requestTraitTree) ++ trees ++ traitTreesV.flatten ++ serviceTrees))
  }

  // TODO: hex
  private def dec2headerDef(decHeader: Int): Tree =
    VAL("header") := LIT(decHeader)

  private def rpcItemTrees(packageName: String, rpc: RpcContent): (Vector[Tree], Vector[Tree]) = {
    val className = f"Request${rpc.name}%s"

    val params = paramsTrees(rpc.attributes)

    val (responseRef, (globalResponseRefs, responseTrees)) = rpc.response match {
      case ReferenceRpcResponse(name) ⇒
        (
          REF(f"Refs.Response$name%s"),
          (Vector.empty, Vector.empty)
        )
      case resp: AnonymousRpcResponse ⇒
        (
          REF(f"Refs.Response${rpc.name}%s"),
          namedResponseItemTrees(packageName, resp.toNamed(rpc.name))
        )
    }

    val headerDef = dec2headerDef(rpc.header)
    val responseRefDef = VAL("Response") := responseRef
    val responseTypeDef = TYPEVAR("Response") := responseRef

    val classTrees = serializationTrees(
      packageName,
      className,
      rpc.attributes,
      rpc.doc
    )

    val deserTrees = deserializationTrees(
      className,
      rpc.attributes
    )

    val objectTrees = Vector(responseRefDef, responseTypeDef) ++ deserTrees

    val (globalRequestRefs, requestTrees) = classWithCompanion(
      packageName,
      className,
      Vector(valueCache(f"${packageName.capitalize}%sRpcRequest"), valueCache("ContainsHeader")),
      params,
      classTrees,
      objectTrees,
      Vector(headerDef),
      rpc.doc
    )

    (
      globalRequestRefs ++ globalResponseRefs,
      requestTrees ++ responseTrees
    )
  }

  private def namedResponseItemTrees(
    packageName: String,
    resp:        RpcResponseContent
  ): (Vector[Tree], Vector[Tree]) = {
    val className = f"Response${resp.name}%s"

    val params = paramsTrees(resp.attributes)

    val headerDef = dec2headerDef(resp.header)

    val serTrees = serializationTrees(
      packageName,
      className,
      resp.attributes,
      resp.doc
    )
    val deserTrees = deserializationTrees(className, resp.attributes)

    classWithCompanion(packageName, className, Vector(valueCache("RpcResponse")), params, serTrees, deserTrees, Vector(headerDef), resp.doc)
  }

  private def relatedIdsDef(attrs: Seq[Attribute], aliasName: String, isPeer: Boolean = false): ValDef = {
    def extractor(attr: AttributeType, ref: String): Option[Tree] = attr match {
      case _: Types.Struct ⇒
        Some(REF(ref) DOT s"_related${aliasName}s": Tree)
      case Types.List(typ) ⇒
        extractor(typ, "_") map (REF(ref) FLATMAP _)
      case Types.Opt(typ) ⇒
        extractor(typ, s"_$ref") map (ext ⇒ REF(ref) MAP (LAMBDA(PARAM(s"_$ref", attrType(typ))) ==> BLOCK(ext)) POSTFIX "getOrElse" APPLY EmptyVector)
      case _: Types.Trait ⇒
        Some(REF(ref) DOT s"_related${aliasName}s")
      case Types.Alias(`aliasName`) ⇒ Some(VECTOR(REF(ref)))
      case Types.Int32 if ref == "id" && aliasName == "UserId" && isPeer ⇒
        Some(
          IF(REF("type") ANY_== REF("Refs.ApiPeerType.Private")) THEN (VECTOR(REF(ref))) ELSE (EmptyVector)
        )
      case Types.Int32 if ref == "id" && aliasName == "GroupId" && isPeer ⇒
        Some(
          IF(REF("type") ANY_== REF("Refs.ApiPeerType.Group")) THEN (VECTOR(REF(ref))) ELSE (EmptyVector)
        )
      case _ ⇒
        None
    }

    VAL(s"_related${aliasName}s", valueCache("IndexedSeq[Int]")) withFlags Flags.LAZY := {
      val extractors = attrs flatMap {
        case Attribute(typ @ Types.Struct("Peer" | "OutPeer"), _, name) ⇒
          extractor(typ, name)
        case Attribute(typ, _, name) ⇒
          extractor(typ, name)
      } filterNot (_ == EmptyVector)

      if (extractors.nonEmpty)
        BLOCK(extractors reduce ((a, b) ⇒ BLOCK(a) INFIX "++" APPLY BLOCK(b)))
      else
        EmptyVector
    }
  }

  private def traitItemTrees(packageName: String, trai: Trait, children: Vector[NamedItem]): (Vector[Tree], Vector[Tree]) = {
    val globalRefs = Vector(
      TYPEVAR(trai.name) := REF(f"$packageName%s.${trai.name}%s"),
      VAL(trai.name) := REF(f"$packageName%s.${trai.name}%s")
    )

    val traitDef = TRAITDEF(trai.name) withFlags Flags.SEALED := BLOCK(
      traitSerializationTrees(trai.name, children) ++
        Vector(
          VAL("header", IntClass).tree,
          VAL("_relatedUserIds", valueCache("IndexedSeq[Int]")): Tree,
          VAL("_relatedGroupIds", valueCache("IndexedSeq[Int]")): Tree
        )
    )

    val objDef = OBJECTDEF(trai.name) := BLOCK(
      traitDeserializationTrees(trai.name, children)
    )

    (globalRefs, Vector(traitDef, objDef))
  }

  private def updateItemTrees(packageName: String, update: Update): (Vector[Tree], Vector[Tree]) = {
    val className = f"Update${update.name}%s"
    val params = paramsTrees(update.attributes)
    val headerDef = dec2headerDef(update.header)

    val serTrees = serializationTrees(
      packageName,
      className,
      update.attributes,
      update.doc
    )

    val deserTrees = deserializationTrees(
      className,
      update.attributes
    )

    val idsTrees = Seq(relatedIdsDef(update.attributes, "UserId"), relatedIdsDef(update.attributes, "GroupId"))

    classWithCompanion(packageName, className, Vector(valueCache("Update")), params, serTrees ++ idsTrees, deserTrees, Vector(headerDef), update.doc)
  }

  private def updateBoxItemTrees(packageName: String, ub: UpdateBox): (Vector[Tree], Vector[Tree]) = {
    val params = paramsTrees(ub.attributes)

    val serTrees = serializationTrees(
      packageName,
      ub.name,
      ub.attributes,
      ub.doc
    )

    val deserTrees = deserializationTrees(
      ub.name,
      ub.attributes
    )

    val headerDef = dec2headerDef(ub.header)

    classWithCompanion(packageName, ub.name, Vector(valueCache("UpdateBox")), params, serTrees, deserTrees, Vector(headerDef), ub.doc)
  }

  private def structItemTrees(packageName: String, struct: Struct): TreesChildren = {
    val params = paramsTrees(struct.attributes)

    val serTrees =
      if (struct.`trait`.isEmpty) {
        serializationTrees(
          packageName,
          struct.name,
          struct.attributes,
          struct.doc
        )
      } else {
        traitChildSerializationTrees(
          packageName,
          struct.name,
          struct.attributes,
          struct.doc
        )
      }

    val deserTrees = deserializationTrees(
      struct.name,
      struct.attributes
    )

    val (parents, traitImplTrees) = struct.`trait` match {
      case Some(traitExt @ TraitExt(_, traitKey)) ⇒
        (
          Vector(typeRef(valueCache(traitExt.name))),
          Vector(VAL("header") := LIT(traitExt.key))
        )
      case None ⇒
        val parents =
          if (struct.name.endsWith("ErrorData"))
            Vector(typeRef(valueCache("ErrorData")))
          else Vector.empty

        (parents, Vector.empty)
    }

    val isPeer = struct._name == "Peer" || struct._name == "OutPeer"
    val idsTrees = Seq(relatedIdsDef(struct.attributes, "UserId", isPeer), relatedIdsDef(struct.attributes, "GroupId", isPeer))

    classWithCompanion(packageName, struct.name, parents, params, serTrees ++ traitImplTrees ++ idsTrees, deserTrees, Vector.empty, struct.doc) match {
      case (globalRefs, trees) ⇒
        (globalRefs, trees, Vector(struct))
    }
  }

  // TODO: use custom json formatters here
  private def enumItemTrees(packageName: String, enum: Enum): (Vector[Tree], Vector[Tree]) = {
    if (enum.values.nonEmpty) {
      val valuesTrees = enum.values map {
        case EnumValue(id, name) ⇒
          VAL(name) withType valueCache(enum.name) := Apply(valueCache("Value"), LIT(id))
      }

      val traitDef = TRAITDEF(enum.name) withParents "Enumeration"

      val typeAlias = TYPEVAR(enum.name) := typeRef(valueCache("Value"))

      val objDef = OBJECTDEF(enum.name) withParents (enum.name) := BLOCK(
        Seq(typeAlias) ++ valuesTrees
      )

      (
        Vector(
          TYPEVAR(enum.name) := REF(f"$packageName%s.${enum.name}%s.${enum.name}%s"),
          VAL(enum.name) := REF(f"$packageName%s.${enum.name}%s")
        ),
          Vector(traitDef, objDef)
      )
    } else {
      deserializationError("Enum attributes should be a non-empty JsArray")
    }
  }

  private def paramsTrees(attributes: Vector[Attribute]): Vector[ValDef] = {
    attributes.sortBy(_.id) map { attr ⇒
      PARAM(attr.name, attrType(attr.typ)).tree
    }
  }

  private def classWithCompanion(
    packageName: String,
    name:        String,
    parents:     Vector[Type],
    params:      Vector[ValDef],
    classTrees:  Vector[Tree],
    objectTrees: Vector[Tree],
    commonTrees: Vector[Tree],
    doc:         Doc
  ): (Vector[Tree], Vector[Tree]) = {
    val ref = REF(f"$packageName%s.$name%s")
    val objRef = VAL(name) := ref

    if (params.isEmpty) {
      (
        Vector(
          TYPEVAR(name) := ref,
          objRef
        ),
          Vector(
            TRAITDEF(name) withParents parents := BLOCK(
              classTrees
            ),
            CASEOBJECTDEF(name) withParents valueCache(name) := BLOCK(
              objectTrees ++ commonTrees
            )
          )
      )
    } else (
      Vector(
        TYPEVAR(name) := ref,
        objRef
      ),
        Vector(
          (CASECLASSDEF(name)
            withFlags Flags.FINAL
            withParents parents
            withParams params := BLOCK(classTrees ++ commonTrees))
            withDoc (generateDoc(doc): _*),
          OBJECTDEF(name) := BLOCK(objectTrees ++ commonTrees)
        )
    )
  }
}
