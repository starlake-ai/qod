package ai.starlake.quack.edge.rls

import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.parser.{CCJSqlParserConstants, CCJSqlParserUtil}

import scala.util.{Failure, Success, Try}

/** A stored mask or row predicate read whole, or refused. JSqlParser's expression entry points stop
  * at a token they cannot read and answer the expression before it (`c => 1` and `c := 1` read as
  * `c`), and its lexer reads `//`, DuckDB's integer division, as the start of a comment, so a text
  * could be stored, or judged, shorter than written: a mask that returns the raw column, a row
  * predicate that keeps rows the administrator meant to filter. Here the whole text must be one
  * expression, every token of it read, no part of it taken as a comment and no `#` (which
  * JSqlParser reads as an operator and DuckDB refuses).
  */
private[edge] object WholeExpression:

  /** `text` lexed to the end: Left(reason) when a part of it is a comment (a line or block comment,
    * `//`), a `#`, or a character the lexer cannot read. The reasons are fixed texts, never the
    * input.
    */
  def lexes(text: String): Either[String, Unit] =
    Try {
      val parser = CCJSqlParserUtil.newParser(text)
      var found  = Option.empty[String]
      var token  = parser.getNextToken
      while found.isEmpty && token != null do
        if token.specialToken != null then
          found = Some(
            "a part the parser reads as a comment (DuckDB's // integer division included)"
          )
        else if token.image == "#" then found = Some("the # operator")
        if token.kind == CCJSqlParserConstants.EOF then token = null
        else token = parser.getNextToken
      found
    } match
      case Success(None)         => Right(())
      case Success(Some(reason)) => Left(reason)
      case Failure(_)            => Left("a character the parser cannot read")

  /** `text` as one expression, read to its end (a condition when `cond`). */
  def parse(text: String, cond: Boolean): Either[String, Expression] =
    lexes(text).flatMap { _ =>
      Try(
        if cond then CCJSqlParserUtil.parseCondExpression(text, false)
        else CCJSqlParserUtil.parseExpression(text, false)
      ) match
        case Success(e) if e != null => Right(e)
        case Success(_)              => Left("an empty expression")
        case Failure(_)              => Left("text that does not parse as one expression")
    }

  /** The same, the reason prefixed for a field (`transformSql`, `predicateSql`). */
  def parseFor(field: String, text: String, cond: Boolean): Either[String, Expression] =
    parse(text, cond).left.map(reason => s"$field holds $reason, so it does not read whole")
