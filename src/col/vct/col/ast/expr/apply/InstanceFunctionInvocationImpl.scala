package vct.col.ast.expr.apply

import vct.col.ast.InstanceFunctionInvocation
import vct.col.print.{Ctx, Doc, DocUtil, Empty, Group, Precedence, Text}
import vct.col.ast.ops.InstanceFunctionInvocationOps

trait InstanceFunctionInvocationImpl[G] extends InstanceFunctionInvocationOps[G] { this: InstanceFunctionInvocation[G] =>
  override def precedence: Int = Precedence.POSTFIX
  override def layout(implicit ctx: Ctx): Doc =
    Group(
      Group(
        assoc(obj) <> "." <> ctx.name(ref) <>
          (if (typeArgs.nonEmpty) Text("<") <> Doc.args(typeArgs) <> ">" else Empty) <>
          "("
      ) <> Doc.args(args) <> ")" <> DocUtil.givenYields(givenMap, yields)
    )
}
