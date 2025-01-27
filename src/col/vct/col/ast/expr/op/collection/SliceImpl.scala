package vct.col.ast.expr.op.collection

import vct.col.ast.{Slice, Type}
import vct.col.print.{Ctx, Doc, Precedence}
import vct.col.ast.ops.SliceOps

trait SliceImpl[G] extends SliceOps[G] { this: Slice[G] =>
  override def t: Type[G] = xs.t

  override def precedence: Int = Precedence.POSTFIX
  override def layout(implicit ctx: Ctx): Doc = assoc(xs) <> "[" <> from <> ".." <> to <> "]"
}