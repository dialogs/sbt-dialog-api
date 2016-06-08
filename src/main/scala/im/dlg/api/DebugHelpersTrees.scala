package im.dlg.api

import treehugger.forest._, definitions._
import treehuggerDSL._

trait DebugHelpersTrees {

  protected val debugTree: Tree = OBJECTDEF("Debug") withFlags PRIVATEWITHIN("api") := BLOCK(
    IMPORT("com.typesafe.config.ConfigFactory"),
    DEF("isDebug", BooleanClass) :=
      REF("ConfigFactory") DOT "load" DOT "getBoolean" APPLY LIT("debug-mode")
  )
}
