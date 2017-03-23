package im.dlg.api

import im.dlg.api.Types.AttributeType

import scala.collection.mutable
import treehugger.forest._, definitions._
import treehuggerDSL._

private[api] trait TreeHelpers {
  val aliasesPrim: Map[String, AttributeType]

  protected var typeMapping = Map.empty[String, NamedItem]

  protected def isChild(name: String) =
    typeMapping.get(name).exists(_.traitExt.isDefined)

  private val symCache: mutable.Map[Name, Symbol] = mutable.Map.empty

  protected def valueCache(name: Name): Symbol = {
    symCache.getOrElseUpdate(name, {
      if (name.isTypeName) RootClass.newClass(name.toTypeName)
      else RootClass.newModule(name.toTermName)
    })
  }

  def indexedSeqType(arg: Type) = appliedType(IndexedSeqClass.typeConstructor, List(arg))
  val EmptyVector: Tree = REF("Vector") DOT "empty"

  protected def attrType(typ: Types.AttributeType): Type = typ match {
    case Types.Int32  ⇒ IntClass
    case Types.Int64  ⇒ LongClass
    case Types.Double ⇒ DoubleClass
    case Types.String ⇒ StringClass
    case Types.Bool   ⇒ BooleanClass
    case Types.Bytes  ⇒ arrayType(ByteClass)
    case struct @ Types.Struct(_) ⇒
      valueCache(s"Refs.${struct.name}")
    case enum @ Types.Enum(_) ⇒
      valueCache(s"Refs.${enum.name}")
    case Types.List(listTyp) ⇒
      indexedSeqType(attrType(listTyp))
    case Types.Opt(optTyp) ⇒
      optionType(attrType(optTyp))
    case trai @ Types.Trait(_) ⇒
      valueCache(s"Refs.${trai.name}")
    case Types.Alias(aliasName) ⇒
      attrType(aliasesPrim(aliasName))
  }

  protected def dealias(typ: Types.AttributeType): AttributeType = typ match {
    case Types.Alias(aliasName) => aliasesPrim(aliasName)
    case other => other
  }

  def XORRIGHT(right: Tree) = REF("Right") APPLY right
  def XORLEFT(left: Tree) = REF("Left") APPLY left

  def xorType(arg1: Type, arg2: Type) = typeRef(NoPrefix, valueCache("Either"), List(arg1, arg2))

  def emptyVector = valueCache("Vector") DOT "empty"
}
