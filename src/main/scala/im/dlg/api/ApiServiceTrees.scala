package im.dlg.api

import treehugger.forest._, definitions._
import treehuggerDSL._

private[api] trait ApiServiceTrees extends TreeHelpers with StringHelperTrees {
  protected val baseServiceTrees: Vector[Tree] = {
    Vector(
      TRAITDEF("Service") := BLOCK(
        TYPEVAR("HandleResult") withFlags Flags.PROTECTED := REF("Either") APPLYTYPE (
          "RpcError",
          "RpcOk"
        ),
        TYPEVAR("HandlerResult[A <: RpcResponse]") withFlags Flags.PROTECTED := REF("Either") APPLYTYPE (
          "RpcError",
          "A"
        ),
        VAL("handleRequestPartial", valueCache("PartialFunction[RpcRequest, ClientData => Future[HandleResult]]")),
        DEF("onFailure", TYPE_REF(REF(PartialFunctionClass) APPLYTYPE ("Throwable", "RpcError"))) :=
          (REF("PartialFunction") DOT "empty" APPLYTYPE ("Throwable", "RpcError")),
        DEF("recoverFailure[A <: RpcResponse]", TYPE_REF(REF(PartialFunctionClass) APPLYTYPE ("Throwable", "HandlerResult[A]"))) withFlags Flags.FINAL :=
          REF("onFailure") DOT "andThen" APPLY LAMBDA(PARAM("e")) ==> BLOCK(REF("Left") APPLY REF("e"))
      ),
      TRAITDEF("BaseClientData") := BLOCK(
        VAL("authId", LongClass),
        VAL("sessionId", LongClass)
      ),
      CASECLASSDEF("AuthData")
        withParams (PARAM("userId", IntClass), PARAM("authSid", IntClass), PARAM("appId", IntClass)),
      CASECLASSDEF("ClientData")
        withParams (
          PARAM("authId", LongClass),
          PARAM("sessionId", LongClass),
          PARAM("authData", optionType(valueCache("AuthData"))),
          PARAM("remoteAddr", optionType(valueCache("String"))) := REF("None")

        )
          withParents valueCache("BaseClientData") := BLOCK {
            DEF("optUserId") := REF("authData") DOT "map" APPLY (WILDCARD DOT "userId")
          },
      CASECLASSDEF("AuthorizedClientData")
        withParams (
          PARAM("authId", LongClass),
          PARAM("sessionId", LongClass),
          PARAM("userId", IntClass),
          PARAM("authSid", IntClass),
          PARAM("appId", IntClass),
          PARAM("remoteAddr", optionType(valueCache("String"))) := REF("None")
        )
          withParents valueCache("BaseClientData"),
      CASECLASSDEF("GuestClientData")
        withParams (PARAM("authId", LongClass), PARAM("sessionId", LongClass), PARAM("remoteAddr", optionType(valueCache("String"))) := REF("None"))
        withParents valueCache("BaseClientData")
    )
  }

  protected def packageApiServiceTrees(packageName: String, items: Vector[Item]): Vector[Tree] = {
    val rpcs = items.filter(_.isInstanceOf[RpcContent])

    if (rpcs.isEmpty) {
      Vector.empty
    } else {
      val handlers: Vector[Tree] = (rpcs map {
        case RpcContent(_, name, attributes, doc, response) ⇒
          val params = attributes map { attr ⇒

            def scalaTyp(typ: Types.AttributeType): Type = typ match {
              case Types.Int32                    ⇒ IntClass
              case Types.Int64                    ⇒ LongClass
              case Types.Bool                     ⇒ BooleanClass
              case Types.Double                   ⇒ DoubleClass
              case Types.String                   ⇒ StringClass
              case Types.Bytes                    ⇒ arrayType(ByteClass)
              case Types.UUID                     ⇒ valueCache("java.util.UUID")
              case enum @ Types.Enum(_)           ⇒ valueCache(s"Refs.${enum.name}.${enum.name}")
              case Types.Opt(optAttrType)         ⇒ optionType(scalaTyp(optAttrType))
              case Types.List(listAttrType)       ⇒ indexedSeqType(scalaTyp(listAttrType))
              case struct @ Types.Struct(_)       ⇒ valueCache(s"Refs.${struct.name}")
              case trai @ Types.Trait(_)          ⇒ valueCache(s"Refs.${trai.name}")
              case alias @ Types.Alias(aliasName) ⇒ scalaTyp(aliasesPrim.get(aliasName).get)
            }

            PARAM(attr.name, scalaTyp(attr.typ)): ValDef
          }

          val respType = response match {
            case _: AnonymousRpcResponse ⇒ f"Response$name%s"
            case named: NamedRpcResponse ⇒ f"Refs.Response${named.name}%s"
          }

          val hname = f"handle$name%s"
          val htype = valueCache(f"Future[HandlerResult[$respType%s]]")

          // workaround for eed3si9n/treehugger#26
          val shname =
            if (params.isEmpty)
              hname + "()"
            else
              hname

          val doHname = "do" + hname.capitalize

          val paramsWithClient = params :+ PARAM("clientData", valueCache("ClientData")).tree
          val attrNamesWithClient = attributes.map(a ⇒ REF(a.name)) :+ REF("clientData")

          Vector(
            DEF(doHname, htype)
              .withFlags(Flags.PROTECTED)
              .withParams(paramsWithClient).tree
              .withDoc(generateDoc(doc): _*),
            DEF(shname, htype)
              .withFlags(Flags.FINAL)
              .withParams(params)
              .withParams(PARAM("clientData", valueCache("ClientData")).withFlags(Flags.IMPLICIT)) :=
              REF(doHname) APPLY attrNamesWithClient DOT "recover" APPLY REF("recoverFailure")
          )
      }).flatten

      val pfType = valueCache("PartialFunction[RpcRequest, ClientData => Future[HandleResult]]")
      val handleRequestDefPF = VAL("handleRequestPartial", pfType) withFlags Flags.OVERRIDE :=
        BLOCK(
          rpcs map {
            case RpcContent(_, name, attributes, _, _) ⇒
              val rqParams: Vector[Tree] = attributes map { attr ⇒
                REF("r") DOT attr.name: Tree
              }

              CASE(REF("r") withType valueCache(f"Request$name%s")) ==> (
                LAMBDA(PARAM("clientData", valueCache("ClientData"))) ==> BLOCK(
                  VAL("f") := (if (rqParams.isEmpty) {
                    REF(f"handle$name%s()") APPLY REF("clientData")
                  } else
                    REF(f"handle$name%s") APPLY rqParams APPLY REF("clientData")),
                  REF("f") DOT "map" APPLY BLOCK(
                    CASE(REF("Right") APPLY REF("rsp")) ==> (
                      REF("Right") APPLY (REF("RpcOk") APPLY REF("rsp"))
                    ),
                    CASE(REF("Left") APPLY REF("err")) ==> (
                      REF("Left") APPLY (REF("err"))
                    )
                  )
                )
              )

          }
        )

      val handleRequestDef = DEF("handleRequest", valueCache("Future[HandleResult]")) withParams (
        PARAM("clientData", valueCache("ClientData")),
        PARAM("request", valueCache(f"${packageName.capitalize}%sRpcRequest"))
      ) := BLOCK(
          REF("handleRequestPartial") APPLY REF("request") APPLY REF("clientData")
        )

      val ecDef: Tree = VAL("ec", valueCache("ExecutionContext")) withFlags (Flags.IMPLICIT, Flags.PROTECTED)

      Vector(
        TRAITDEF(f"${packageName.capitalize}Service")
          withParents "Service" := BLOCK(
            Vector(ecDef, handleRequestDefPF, handleRequestDef) ++
              handlers
          )
      )
    }
  }
}
