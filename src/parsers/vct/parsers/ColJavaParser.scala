package vct.parsers

import org.antlr.v4.runtime.{CharStream, CommonTokenStream}
import vct.antlr4.generated._
import vct.col.ast.GlobalDeclaration
import vct.col.origin.{ExpectedError, Origin}
import vct.parsers.transform.{BlameProvider, JavaToCol, OriginProvider}

import scala.jdk.CollectionConverters.CollectionHasAsScala

case class ColJavaParser(override val origin: Origin,
                         override val blameProvider: BlameProvider) extends Parser(origin, blameProvider) {
  override def parse[G](stream: CharStream): ParseResult[G] = parse(stream, true)

  def parse[G](stream: CharStream, specCommentsNeeded: Boolean): ParseResult[G] = {
    try {
      val lexer = new LangJavaLexer(stream)
      val tokens = new CommonTokenStream(lexer)
      val parser = new JavaParser(tokens)
      /* This is needed for JavaBIP, to allow Perm at the top level.
         If changed, this should also be made consistent with the parser below. */
      if (!specCommentsNeeded) {
        parser.specLevel = 1
      }

      val (errors, tree) = noErrorsOrThrow(origin, parser, lexer) {
        val errors = expectedErrors(tokens, LangJavaLexer.EXPECTED_ERROR_CHANNEL, LangJavaLexer.VAL_EXPECT_ERROR_OPEN, LangJavaLexer.VAL_EXPECT_ERROR_CLOSE)
        val tree = parser.compilationUnit()
        (errors, tree)
      }

      val decls = JavaToCol[G](origin, blameProvider, errors).convert(tree)
      ParseResult(decls, errors.map(_._3))
    } catch {
      case m: MatchError =>
        throw ParseMatchError(m.getMessage())
    }
  }

  def parseExpr[G](stream: CharStream, specCommentsNeeded: Boolean): (vct.col.ast.Expr[G], Seq[ExpectedError]) = {
    try {
      val lexer = new LangJavaLexer(stream)
      val tokens = new CommonTokenStream(lexer)
      val errors = expectedErrors(tokens, LangJavaLexer.EXPECTED_ERROR_CHANNEL, LangJavaLexer.VAL_EXPECT_ERROR_OPEN, LangJavaLexer.VAL_EXPECT_ERROR_CLOSE)
      val parser = new JavaParser(tokens)
      if (!specCommentsNeeded) {
        parser.specLevel = 1
      }

      val tree = noErrorsOrThrow(origin, parser, lexer) {
        parser.expr()
      }
      val decls = JavaToCol[G](origin, blameProvider, errors).convert(tree)
      (decls, errors.map(_._3))
    } catch {
      case m: MatchError =>
        throw ParseMatchError(m.getMessage())
    }
  }
}