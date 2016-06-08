package im.dlg.api

import im.dlg.api.Categories.Category
import spray.json._

private[api] case class Alias(`type`: String, alias: String) {
  val typ = `type`
}

private[api] object Types {
  sealed trait AttributeType

  case object Int32 extends AttributeType
  case object Int64 extends AttributeType
  case object Double extends AttributeType
  case object String extends AttributeType
  case object Bool extends AttributeType
  case object Bytes extends AttributeType

  sealed trait NamedAttributeType extends AttributeType {
    val name: String
  }

  case class Struct(_name: String) extends NamedAttributeType {
    val name = ApiPrefix + _name
  }
  case class Enum(_name: String) extends NamedAttributeType {
    val name = ApiPrefix + _name
  }
  case class Alias(_name: String) extends NamedAttributeType {
    val name = ApiPrefix + _name
  }
  case class List(typ: AttributeType) extends AttributeType
  case class Opt(typ: AttributeType) extends AttributeType
  case class Trait(_name: String) extends NamedAttributeType {
    val name = ApiPrefix + _name
  }

}

private[api] object Categories {
  object Category {
    def isVisible: PartialFunction[Category, Boolean] = {
      case Hidden | Danger ⇒ false
      case Full | Compact  ⇒ true
    }
  }

  sealed trait Category

  case object Hidden extends Category
  case object Danger extends Category
  case object Full extends Category
  case object Compact extends Category
}

private[api] case class AttributeDoc(`type`: String, argument: String, category: Categories.Category, description: String)

private[api] case class Doc(generalDoc: String, attributeDocs: Vector[AttributeDoc])

private[api] case class Attribute(`type`: Types.AttributeType, id: Int, name: String) {
  val typ = `type`

  def withType(typ: Types.AttributeType) = copy(`type` = typ)
}

private[api] trait Named {
  def name: String
}

private[api] trait Item {
  def traitExt: Option[TraitExt]
}

private[api] trait NamedItem extends Item with Named

private[api] trait RpcResponse
private[api] case class AnonymousRpcResponse(header: Int, attributes: Vector[Attribute], doc: Doc) extends Item with RpcResponse {
  def traitExt = None
  def toNamed(name: String) = RpcResponseContent(name, header, attributes, doc)
}

private[api] trait NamedRpcResponse extends NamedItem with RpcResponse

private[api] case class ReferenceRpcResponse(name: String) extends NamedRpcResponse {
  def traitExt = None
}
private[api] case class RpcResponseContent(name: String, header: Int, attributes: Vector[Attribute], doc: Doc) extends NamedRpcResponse {
  def traitExt = None
}

private[api] case class RpcContent(header: Int, name: String, attributes: Vector[Attribute], doc: Doc, response: RpcResponse) extends NamedItem {
  def traitExt = None
}

private[api] case class Trait(_name: String) extends NamedItem {
  val name = ApiPrefix + _name
  def traitExt = None
}
private[api] case class TraitExt(_name: String, key: Int) {
  val name = ApiPrefix + _name
}

private[api] case class UpdateBox(name: String, header: Int, attributes: Vector[Attribute], doc: Doc) extends NamedItem {
  def traitExt = None
}

private[api] case class Update(name: String, header: Int, attributes: Vector[Attribute], doc: Doc) extends NamedItem {
  def traitExt = None
}

private[api] case class Struct(_name: String, attributes: Vector[Attribute], doc: Doc, `trait`: Option[TraitExt]) extends NamedItem {
  val name = ApiPrefix + _name
  def traitExt = `trait`
}

private[api] case class Enum(_name: String, values: Vector[EnumValue]) extends NamedItem {
  val name = ApiPrefix + _name
  def traitExt = None
}
private[api] case class EnumValue(id: Int, name: String)

private[api] trait JsonFormats extends DefaultJsonProtocol with Hacks {
  val aliases: Map[String, String]

  implicit val traitFormat = jsonFormat[String, Trait](Trait.apply, "name")
  implicit val traitExtFormat = jsonFormat[String, Int, TraitExt](TraitExt.apply, "name", "key")

  implicit val enumValueFormat = jsonFormat2(EnumValue)
  implicit val enumFormat = jsonFormat[String, Vector[EnumValue], Enum](Enum.apply, "name", "values")

  implicit object aliasFormat extends RootJsonFormat[Alias] {
    def write(typ: Alias): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case JsObject(fields) ⇒
        val optAlias = for {
          jsAlias ← fields.get("alias")
          jsTyp ← fields.get("type")
        } yield {
          jsAlias match {
            case JsString(alias) ⇒
              jsTyp match {
                case JsString(typ) ⇒
                  Alias(typ, alias)
                case _ ⇒
                  deserializationError("Alias type should be a JsString")
              }
            case _ ⇒
              deserializationError("Alias alias should be a JsString")
          }
        }

        optAlias getOrElse deserializationError("Both type and alias fields are required for an alias")
      case _ ⇒
        deserializationError("Alias should be a JsObject")
    }
  }

  def primitiveType(typ: String): Types.AttributeType =
    typ match {
      case "int32"     ⇒ Types.Int32
      case "int64"     ⇒ Types.Int64
      case "double"    ⇒ Types.Double
      case "string"    ⇒ Types.String
      case "bool"      ⇒ Types.Bool
      case "bytes"     ⇒ Types.Bytes
      case unsupported ⇒ deserializationError(s"Unknown type $unsupported%s")
    }

  def attributeCategory: PartialFunction[String, Categories.Category] = {
    case "full"    ⇒ Categories.Full
    case "hidden"  ⇒ Categories.Hidden
    case "danger"  ⇒ Categories.Danger
    case "compact" ⇒ Categories.Compact
  }

  def normalizeAlias(name: String): String = name.split("_").map(_.capitalize).mkString("")

  //doc is one or more strings, and attribute doc describing attributes
  implicit object docFormat extends RootJsonFormat[Doc] {
    override def write(obj: Doc): JsValue = throw new NotImplementedError()

    override def read(value: JsValue): Doc = value match {
      case JsArray(elems) ⇒
        (elems foldLeft Doc("", Vector.empty[AttributeDoc])) { (acc, elem) ⇒
          elem match {
            case JsString(str) ⇒ acc.copy(generalDoc = acc.generalDoc + str)
            case obj: JsObject ⇒ acc.copy(attributeDocs = acc.attributeDocs :+ attributeDocFormat.read(obj))
            case _             ⇒ deserializationError("AttirbuteDoc should be JsString or JsObject")
          }
        }
      case _ ⇒ deserializationError("Doc should be JsArray")
    }
  }

  implicit object categoryFormat extends RootJsonFormat[Categories.Category] {
    override def write(obj: Category): JsValue = throw new NotImplementedError()

    override def read(value: JsValue): Category = value match {
      case JsString(cat) ⇒ attributeCategory(cat)
      case _             ⇒ deserializationError("AttributeDoc Category should be JsString")
    }
  }

  implicit object attributeTypeFormat extends RootJsonFormat[Types.AttributeType] {
    def write(typ: Types.AttributeType): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case JsString(typName) ⇒ primitiveType(typName)

      case obj: JsObject ⇒
        obj.getFields("type", "childType") match {
          case Seq(JsString("struct"), JsString(structName)) ⇒
            Types.Struct(structName)
          case Seq(JsString("enum"), JsString(enumName)) ⇒
            Types.Enum(enumName)
          case Seq(JsString("list"), childType) ⇒
            Types.List(read(childType))
          case Seq(JsString("opt"), childType) ⇒
            Types.Opt(read(childType))
          case Seq(JsString("alias"), JsString(aliasName)) ⇒
            Types.Alias(normalizeAlias(aliasName))
          //aliases.get(aliasName) map (t ⇒ read(JsString(t))) getOrElse deserializationError(f"Unknown alias $aliasName%s")
          case Seq(JsString("trait"), JsString(traitName)) ⇒
            Types.Trait(traitName)
        }
      case _ ⇒
        deserializationError("Attribute type should be JsString or JsObject")
    }
  }
  implicit object attributeFormat extends RootJsonFormat[Attribute] {
    def write(attr: Attribute): JsValue = throw new NotImplementedError()

    def read(value: JsValue): Attribute = value match {
      case obj: JsObject ⇒
        val optAttribute = for {
          jsTyp ← obj.fields.get("type")
          jsId ← obj.fields.get("id")
          jsName ← obj.fields.get("name")
        } yield {
          jsId match {
            case JsNumber(id) ⇒
              jsName match {
                case JsString(unhackedName) ⇒
                  val name = hackAttributeName(unhackedName)
                  val typ = attributeTypeFormat.read(jsTyp)
                  Attribute(typ, id.toInt, name)
                case _ ⇒ deserializationError("Attribute name should be JsString")
              }
            case _ ⇒ deserializationError("Attribute type should be JsNumber")
          }
        }

        optAttribute getOrElse (deserializationError("Not enough fields for attribute"))
      case _ ⇒ deserializationError("Attribute should be a JsObject")
    }
  }

  implicit val anonymousRpcResponseFormat = jsonFormat3(AnonymousRpcResponse)
  implicit val referenceRpcResponseFormat = jsonFormat1(ReferenceRpcResponse)
  implicit val rpcResponseContentFormat = jsonFormat4(RpcResponseContent)

  implicit object rpcResponseFormat extends RootJsonFormat[RpcResponse] {
    def write(attr: RpcResponse): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case obj: JsObject ⇒
        obj.fields.get("type") match {
          case Some(JsString(typ)) ⇒
            readTyped(typ, obj)
          case _ ⇒
            deserializationError("RpcResponse type should be present and be a JsString")
        }

      case _ ⇒
        deserializationError("RpcResponse should be a JsObject")
    }

    def readTyped(typ: String, obj: JsObject): RpcResponse = {
      typ match {
        case "anonymous" ⇒
          /*
          val optHeader = obj.fields.get("header") map {
            case JsNumber(header) => header.toInt
            case _ => deserializationError("RpcResponse header should be JsString")
          }

          val optAttributes: Option[Vector[Attribute]] = obj.fields.get("attributes") map {
            case JsArray(fields) =>
              fields map attributeFormat.read
            case _ => deserializationError("RpcResponse attributes should be JsArray")
          }

          AnonymousRpcResponse(optHeader, optAttributes getOrElse (Vector.empty))
           */
          anonymousRpcResponseFormat.read(obj)
        case "reference" ⇒
          referenceRpcResponseFormat.read(obj)
      }
    }
  }

  implicit object structFormat extends RootJsonFormat[Struct] {
    def write(attr: Struct): JsValue = throw new NotImplementedError()

    def read(value: JsValue) = value match {
      case obj: JsObject ⇒
        obj.getFields("name", "attributes", "doc") match {
          case Seq(JsString(name), JsArray(jsAttributes), jsDoc) ⇒
            val attributes = jsAttributes map attributeFormat.read
            val doc = docFormat.read(jsDoc)

            val traitExtOpt: Option[TraitExt] = obj.fields.get("trait") map {
              case obj: JsObject ⇒ traitExtFormat.read(obj)
              case _             ⇒ deserializationError("Expecting traitExt to be a JsObject")
            }

            Struct(name, attributes, doc, traitExtOpt)
          case _ ⇒ deserializationError("Both name and attributes are required for Struct")
        }

      case _ ⇒
        deserializationError("RpcResponse should be a JsObject")
    }
  }

  implicit val rpcContentFormat = jsonFormat5(RpcContent)
  implicit val updateBoxFormat = jsonFormat4(UpdateBox)
  implicit val updateFormat = jsonFormat4(Update)
  implicit val attributeDocFormat = jsonFormat4(AttributeDoc)
}
