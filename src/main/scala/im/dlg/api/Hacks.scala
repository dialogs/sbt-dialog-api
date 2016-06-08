package im.dlg.api

trait Hacks {
  def hackAttributeName(name: String): String = {
    if (name == "uid")
      "userId"
    else if (name.endsWith("Uid"))
      name.take(name.length - 3) + "UserId"
    else if (name == "rid")
      "randomId"
    else
      name
  }

  def extTypeName(structName: String): String = structName match {
    //case "MessageContent" => "type"
    case _ ⇒ "ext"
  }

  def prettify(source: String): String = {
    source.replace("doParse: Unit", "doParse(): Unit")
  }
}
