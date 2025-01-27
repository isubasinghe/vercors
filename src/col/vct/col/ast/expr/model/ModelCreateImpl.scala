package vct.col.ast.expr.model

import vct.col.ast.{ModelCreate, TVoid, Type}
import vct.col.print._
import vct.col.ast.ops.ModelCreateOps

trait ModelCreateImpl[G] extends ModelCreateOps[G] { this: ModelCreate[G] =>
  override def t: Type[G] = TVoid()

  override def precedence: Int = Precedence.POSTFIX
  override def layout(implicit ctx: Ctx): Doc =
    Group(assoc(model) <> "." <> "create" <> "(" <> Doc.args(Seq(init)) <> ")")
}