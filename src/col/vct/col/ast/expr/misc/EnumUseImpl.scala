package vct.col.ast.expr.misc

import vct.col.ast.{EnumUse, TEnum, Type}
import vct.col.print._
import vct.col.ast.ops.EnumUseOps

trait EnumUseImpl[G] extends EnumUseOps[G] { this: EnumUse[G] =>
  override def t: Type[G] = TEnum(enum)

  override def precedence: Int = Precedence.POSTFIX
  override def layout(implicit ctx: Ctx): Doc =
    Text(ctx.name(enum)) <> "." <> ctx.name(const)
}
