package im.dlg.api

import treehugger.forest._
import treehuggerDSL._

private[api] trait CodecTrees extends TreeHelpers {
  protected def codecTrees(packages: Vector[(String, Vector[Item])]): Tree = {
    val (requests, responses, structs, ubs) =
      packages.foldLeft[(Vector[(RpcContent, String)], Vector[(NamedRpcResponse, String)], Vector[(Struct, String)], Vector[(UpdateBox, String)])](
        (Vector.empty, Vector.empty, Vector.empty, Vector.empty)
      ) {
          case (acc, (packageName, items)) ⇒
            val newItems = items.foldLeft[(Vector[(RpcContent, String)], Vector[(NamedRpcResponse, String)], Vector[(Struct, String)], Vector[(UpdateBox, String)])](
              (Vector.empty, Vector.empty, Vector.empty, Vector.empty)
            ) {
                case ((rqAcc, rspAcc, structAcc, ubAcc), r: RpcContent) ⇒
                  val rq = (r, packageName)
                  r.response match {
                    case rsp: AnonymousRpcResponse ⇒
                      (rqAcc :+ rq,
                        rspAcc :+ (rsp.toNamed(r.name), packageName),
                        structAcc,
                        ubAcc)
                    case _ ⇒ (rqAcc :+ rq, rspAcc, structAcc, ubAcc)
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
        IMPORT("akka.util.ByteString"),
        IMPORT("akka.util.ByteIterator"),
        IMPORT("im.dlg.codec._"),
        IMPORT("com.google.protobuf.CodedInputStream")
      ) ++ requestCodecTrees(requests)
        ++ responseCodecTrees(responses)
        ++ structCodecTrees(structs)
        ++ updateBoxCodecTrees(ubs)
    )
  }

  private def structCodecTrees(
    structs: Vector[(Struct, String)]
  ): Vector[Tree] = {
    structs map {
      case (struct, packageName) ⇒
        val structType = f"$packageName%s.${struct.name}%s"

        codecTree(packageName, struct.name, "")
    }
  }

  private def updateBoxCodecTrees(
    ubs: Vector[(UpdateBox, String)]
  ): Vector[Tree] = {
    val ubCodecs = ubs map {
      case (ub, packageName) ⇒
        codecTree(packageName, ub.name, "")
    }

    val codecByHeader =
      REF("header") MATCH ubs.foldLeft(
        Vector.empty[treehugger.forest.CaseDef]
      ) {
        case (acc, (ub, packageName)) ⇒
          acc :+ (CASE(
            REF(f"h if h == $packageName%s.${ub.name}%s.header")
          ) ==> REF(
              f"${ub.name}%sCodec"
            ))
      }

    val writeByType =
      REF("ub") MATCH ubs.foldLeft(
        Vector.empty[treehugger.forest.CaseDef]
      ) {
        case (acc, (ub, packageName)) ⇒
          acc :+ (CASE(REF(f"x: $packageName%s.${ub.name}%s")) ==> REF(
            f"${ub.name}%sCodec.write(x)"
          ))
      }

    val updateBoxCodec = OBJECTDEF("UpdateBoxCodec") withParents ("Codec[UpdateBox]") := BLOCK(
      DEF("write", valueCache("ByteString")) withParams (PARAM("o", valueCache("UpdateBox"))) := BLOCK(
        VAL("ub") := REF("o"),
        VAL("ubBytes") := writeByType,
        REF("uint32.write(ub.header.toLong) ++ sizedBytes.write(ubBytes)")
      ),
      DEF("read", valueCache("UpdateBox")) withParams (PARAM("it", valueCache("ByteIterator"))) := BLOCK(
        VAL("header") := REF("uint32.read(it)"),
        VAL("codec") := codecByHeader,
        REF("codec.read(sizedBytes.read(it))")
      )
    )

    ubCodecs :+ updateBoxCodec
  }

  private def requestCodecTrees(
    requests: Vector[(RpcContent, String)]
  ): Vector[Tree] = {
    val rqCodecs = requests map {
      case (RpcContent(_, name, _, _, _), packageName) ⇒
        codecTree(packageName, name, "Request")
    }

    val codecByHeader =
      REF("header") MATCH requests.foldLeft(
        Vector.empty[treehugger.forest.CaseDef]
      ) {
        case (acc, (request, packageName)) ⇒
          acc :+ (CASE(
            REF(f"h if h == $packageName%s.Request${request.name}%s.header")
          ) ==> REF(
              f"Request${request.name}%sCodec"
            ))
      }

    val writeByType =
      REF("rq") MATCH requests.foldLeft(
        Vector.empty[treehugger.forest.CaseDef]
      ) {
        case (acc, (request, packageName)) ⇒
          acc :+ (CASE(REF(f"x: $packageName%s.Request${request.name}%s")) ==> REF(
            f"Request${request.name}%sCodec.write(x)"
          ))
      }

    val rpcRequestCodec = OBJECTDEF("RpcRequestCodec") withParents ("Codec[RpcRequest]") := BLOCK(
      DEF("write", valueCache("ByteString")) withParams (PARAM("o", valueCache("RpcRequest"))) := BLOCK(
        VAL("rq") := REF("o"),
        VAL("bodyBytes") := writeByType,
        REF("uint32.write(rq.header.toLong) ++ sizedBytes.write(bodyBytes)")
      ),
      DEF("read", valueCache("RpcRequest")) withParams (PARAM("it", valueCache("ByteIterator"))) := BLOCK(
        VAL("header") := REF("uint32.read(it)"),
        VAL("codec") := codecByHeader,
        REF("codec.read(sizedBytes.read(it))")
      )
    )

    val requestCodec = OBJECTDEF("RequestCodec") withParents ("Codec[Request]") := BLOCK(
      DEF("write", valueCache("ByteString")) withParams (PARAM("o", valueCache("Request"))) := BLOCK(
        REF("uint8.write(1) ++ RpcRequestCodec.write(o.body)")
      ),
      DEF("read", valueCache("Request")) withParams (PARAM("it", valueCache("ByteIterator"))) := BLOCK(
        REF("uint8.read(it)"),
        REF("Request(RpcRequestCodec.read(it))")
      )
    )

    rqCodecs :+ requestCodec :+ rpcRequestCodec
  }

  private def responseCodecTrees(
    responses: Vector[(NamedRpcResponse, String)]
  ): Vector[Tree] = {
    val rspCodecs = responses map {
      case (response, packageName) ⇒
        val rspType = f"$packageName%s.Response${response.name}%s"

        codecTree(packageName, response.name, "Response")
    }

    val codecByHeader =
      REF("header") MATCH responses.foldLeft(
        Vector.empty[treehugger.forest.CaseDef]
      ) {
        case (acc, (response, packageName)) ⇒
          acc :+ (CASE(
            REF(f"h if h == $packageName%s.Response${response.name}%s.header")
          ) ==> REF(
              f"Response${response.name}%sCodec"
            ))
      }

    val writeByType =
      REF("rq") MATCH responses.foldLeft(
        Vector.empty[treehugger.forest.CaseDef]
      ) {
        case (acc, (response, packageName)) ⇒
          acc :+ (CASE(REF(f"x: $packageName%s.Response${response.name}%s")) ==> REF(
            f"Response${response.name}%sCodec.write(x)"
          ))
      }

    val rpcRspCodec = OBJECTDEF("RpcResponseCodec") withParents ("Codec[RpcResponse]") := BLOCK(
      DEF("write", valueCache("ByteString")) withParams (PARAM("o", valueCache("RpcResponse"))) := BLOCK(
        VAL("rq") := REF("o"),
        VAL("bodyBytes") := writeByType,
        REF("uint32.write(rq.header.toLong) ++ sizedBytes.write(bodyBytes)")
      ),
      DEF("read", valueCache("RpcResponse")) withParams (PARAM("it", valueCache("ByteIterator"))) := BLOCK(
        VAL("header") := REF("uint32.read(it)"),
        VAL("codec") := codecByHeader,
        REF("codec.read(sizedBytes.read(it))")
      )
    )

    rspCodecs :+ rpcRspCodec
  }

  private def codecTree(
    packageName: String,
    name:        String,
    prefix:      String
  ): Tree = {
    val typ = f"$packageName%s.${prefix.capitalize}%s$name%s"

    OBJECTDEF(f"$prefix%s$name%sCodec") withParents f"Codec[$typ%s]" := BLOCK(
      DEF("write", valueCache("ByteString")) withParams (PARAM("o", valueCache(typ))) :=
        REF("ByteString") APPLY (REF("o") DOT "toByteArray"),

      DEF("read", valueCache(typ)) withParams (PARAM("it", valueCache("ByteIterator"))) :=
        REF(typ) DOT "parseFrom" APPLY (REF("CodedInputStream") DOT "newInstance" APPLY (REF("it") DOT "asInputStream")) MATCH (
          CASE(REF("Right(res)")) ==> REF("res"),
          CASE(REF("Left(err)")) ==> REF("throw new RuntimeException(\"Failed to parse " + typ + ": \" + err)")
        )
    )
  }
}
