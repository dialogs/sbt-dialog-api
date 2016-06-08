package im.dlg.api

import treehugger.forest._
import treehuggerDSL._

private[api] trait CodecTrees extends TreeHelpers {
  protected def codecTrees(packages: Vector[(String, Vector[Item])]): Tree = {
    val (requests, responses, structs, ubs) =
      packages.foldLeft[(Vector[(RpcContent, String)], Vector[(NamedRpcResponse, String)], Vector[(Struct, String)], Vector[(UpdateBox, String)])]((Vector.empty, Vector.empty, Vector.empty, Vector.empty)) {
        case (acc, (packageName, items)) ⇒
          val newItems = items.foldLeft[(Vector[(RpcContent, String)], Vector[(NamedRpcResponse, String)], Vector[(Struct, String)], Vector[(UpdateBox, String)])]((Vector.empty, Vector.empty, Vector.empty, Vector.empty)) {
            case ((rqAcc, rspAcc, structAcc, ubAcc), r: RpcContent) ⇒
              val rq = (r, packageName)
              r.response match {
                case rsp: AnonymousRpcResponse ⇒ (rqAcc :+ rq, rspAcc :+ (rsp.toNamed(r.name), packageName), structAcc, ubAcc)
                case _                         ⇒ (rqAcc :+ rq, rspAcc, structAcc, ubAcc)
              }
            case ((rqAcc, rspAcc, structAcc, ubAcc), r: RpcResponseContent) ⇒
              (rqAcc, rspAcc :+ (r, packageName), structAcc, ubAcc)
            case ((rqAcc, rspAcc, structAcc, ubAcc), s: Struct) ⇒
              (rqAcc, rspAcc, structAcc :+ (s, packageName), ubAcc)
            case ((rqAcc, rspAcc, structAcc, ubAcc), ub: UpdateBox) ⇒
              (rqAcc, rspAcc, structAcc, ubAcc :+ (ub, packageName))
            case (acc, r) ⇒
              acc
          }
          (
            acc._1 ++ newItems._1,
            acc._2 ++ newItems._2,
            acc._3 ++ newItems._3,
            acc._4 ++ newItems._4
          )
      }

    PACKAGEOBJECTDEF("codecs") := BLOCK(
      Vector(
        IMPORT("im.dlg.server.mtproto.codecs._"),
        IMPORT("com.google.protobuf.CodedInputStream"),
        IMPORT("scodec.bits._"),
        IMPORT("scodec._"),
        IMPORT("scodec.codecs._"),

        DEF("protoPayload[A]") withParams PARAM("payloadCodec", valueCache("Codec[A]")) :=
          NEW(REF("PayloadCodec[A]")) APPLY REF("payloadCodec")
      ) ++ requestCodecTrees(requests)
        ++ responseCodecTrees(responses)
        ++ structCodecTrees(structs)
        ++ updateBoxCodecTrees(ubs)
    )
  }

  private def structCodecTrees(structs: Vector[(Struct, String)]): Vector[Tree] = {
    structs map {
      case (struct, packageName) ⇒
        val structType = f"$packageName%s.${struct.name}%s"

        codecTree(packageName, struct.name, "")
    }
  }

  private def updateBoxCodecTrees(ubs: Vector[(UpdateBox, String)]): Vector[Tree] = {
    val ubCodecs = ubs map {
      case (ub, packageName) ⇒
        codecTree(packageName, ub.name, "")
    }

    val updateBoxCodec = VAL("UpdateBoxCodec") :=
      ubs.foldLeft[Tree](REF("discriminated[UpdateBox]") DOT "by" APPLY REF("uint32")) {
        case (acc, (ub, packageName)) ⇒
          acc DOT "typecase" APPLY (
            REF(f"$packageName%s.${ub.name}%s.header.toLong"),
            REF("PayloadCodec") APPLY REF(f"${ub.name}%sCodec")
          )
      }

    ubCodecs :+ updateBoxCodec
  }

  private def requestCodecTrees(requests: Vector[(RpcContent, String)]): Vector[Tree] = {
    val rqCodecs = requests map {
      case (RpcContent(_, name, _, _, _), packageName) ⇒
        codecTree(packageName, name, "Request")
    }

    val rpcRqCodec = VAL("RpcRequestCodec") :=
      requests.foldLeft[Tree](REF("discriminated[RpcRequest]") DOT "by" APPLY REF("uint32")) {
        case (acc, (request, packageName)) ⇒
          acc DOT "typecase" APPLY (
            REF(f"$packageName%s.Request${request.name}%s.header.toLong"),
            REF("PayloadCodec") APPLY REF(f"Request${request.name}%sCodec")
          )
      }

    val requestCodec = VAL("RequestCodec") :=
      REF("discriminated[Request]") DOT "by" APPLY REF("uint8") DOT "typecase" APPLY (
        LIT(1),
        REF("RpcRequestCodec") DOT "widenOpt" APPLY (
          REF("Request.apply"),
          REF("Request.unapply")
        )
      )

    rqCodecs :+ rpcRqCodec :+ requestCodec
  }

  private def responseCodecTrees(responses: Vector[(NamedRpcResponse, String)]): Vector[Tree] = {
    val rspCodecs = responses map {
      case (response, packageName) ⇒
        val rspType = f"$packageName%s.Response${response.name}%s"

        codecTree(packageName, response.name, "Response")
    }

    val rpcRspCodec = VAL("RpcResponseCodec") :=
      responses.foldLeft[Tree](REF("discriminated[RpcResponse]") DOT "by" APPLY REF("uint32")) {
        case (acc, (response, packageName)) ⇒
          acc DOT "typecase" APPLY (
            REF(f"$packageName%s.Response${response.name}%s.header.toLong"),
            REF("PayloadCodec") APPLY REF(f"Response${response.name}%sCodec")
          )
      }

    rspCodecs :+ rpcRspCodec
  }

  private def codecTree(packageName: String, name: String, prefix: String): Tree = {
    val typ = f"$packageName%s.${prefix.capitalize}%s$name%s"

    OBJECTDEF(f"$prefix%s$name%sCodec") withParents f"Codec[$typ%s]" := BLOCK(
      DEF("sizeBound") := REF("SizeBound") DOT "unknown",
      DEF("encode") withParams PARAM("r", valueCache(typ)) :=
        REF("Attempt") DOT "successful" APPLY (
          REF("BitVector") APPLY (
            REF("r") DOT "toByteArray"
          )
        ),
      DEF("decode") withParams PARAM("bv", valueCache("BitVector")) :=
        REF(typ) DOT "parseFrom" APPLY (
          REF("CodedInputStream") DOT "newInstance" APPLY (REF("bv") DOT "toByteBuffer")
        ) MATCH (
            CASE(REF("Left") APPLY REF("error")) ==> (
              REF("Attempt") DOT "failure" APPLY (REF("Err") APPLY REF("error"))
            ),
              CASE(REF("Right") APPLY REF("r")) ==> (
                REF("Attempt.successful") APPLY (REF("DecodeResult") APPLY (REF("r"), REF("BitVector") DOT "empty"))
              )
          )
    )
  }
}
