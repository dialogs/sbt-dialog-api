package im.dlg.api

import spray.json._
import scala.reflect.runtime.universe._

private[api] trait JsonHelpers {
  implicit class ValidableJsonObject(obj: JsObject) {
    def withField[A](key: String, errorPrefix: String)(f: JsValue ⇒ A): A = {
      obj.fields.get(key) match {
        case Some(value) ⇒ f(value)
        case None ⇒
          val pref = if (errorPrefix.isEmpty) {
            ""
          } else {
            errorPrefix + " "
          }
          deserializationError(f"$pref%s$key%s is required")
      }
    }

    def withField[A](key: String)(f: JsValue ⇒ A): A = withField[A](key, "")(f)

    def withObjectField[A](key: String)(f: JsObject ⇒ A): A = withField(key) {
      case o: JsObject ⇒ f(o)
      case unexpected  ⇒ throw new Exception(s"$key should be a JsObject but got: $unexpected")
    }
  }
}
