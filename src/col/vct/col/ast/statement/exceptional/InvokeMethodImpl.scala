package vct.col.ast.statement.exceptional

import vct.col.ast.InvokeMethod
import vct.col.print.{Ctx, Doc, DocUtil, Empty, Group, Text}
import vct.col.ast.ops.InvokeMethodOps

trait InvokeMethodImpl[G] extends InvokeMethodOps[G] { this: InvokeMethod[G] =>
  override def layout(implicit ctx: Ctx): Doc =
    Group(
      Group(
        obj.show <> "." <> ctx.name(ref) <>
          (if (typeArgs.nonEmpty) Text("<") <> Doc.args(typeArgs) <> ">" else Empty) <>
          "("
      ) <> Doc.args(args ++ outArgs) <> ")" <> DocUtil.givenYields(givenMap, yields) <> ";"
    )
}
