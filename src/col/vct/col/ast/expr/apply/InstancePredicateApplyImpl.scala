package vct.col.ast.expr.apply

import vct.col.ast.InstancePredicateApply
import vct.col.print.{Ctx, Doc, Group, Precedence, Text}
import vct.col.ast.ops.InstancePredicateApplyOps

trait InstancePredicateApplyImpl[G] extends InstancePredicateApplyOps[G] { this: InstancePredicateApply[G] =>
  override def precedence: Int = Precedence.PREFIX
  override def layout(implicit ctx: Ctx): Doc =
    Group(Text("[") <> perm <> "]" <> obj.bind(Precedence.POSTFIX) <> "." <> ctx.name(ref) <> "(" <> Doc.args(args) <> ")")
}
