package ai.starlake.quack.edge.cls

import java.util.Locale
import ai.starlake.quack.edge.rls.{SqlCommentScan, WholeExpression}
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.{Function => SqlFunction}
import net.sf.jsqlparser.statement.select.Select

import scala.util.{Failure, Success, Try}

/** Strict-containment validator for free-form transform expressions on column policies. Runs once
  * at policy create/update time. Pure (no I/O, no DuckDB connection).
  *
  * A `transform_sql` value must be ONE scalar DuckDB expression that:
  *   1. Parses whole as one expression ([[ai.starlake.quack.edge.rls.WholeExpression]]).
  *   2. References only the protected column, read as the rewriter reads it when it splices the
  *      mask ([[ResidualColumns.references]]: every column node outside a lambda's parameters,
  *      every time keyword but a SQL value function's call, no column reference held as another
  *      node), names compared as DuckDB folds them.
  *   3. Contains no subqueries (parenthesed SELECTs).
  *   4. Calls no side-effect or escape functions (denylist + `pragma_*` prefix).
  *   5. Is bounded in size (<= 1024 chars in canonical form).
  *   6. Holds only allowed mask forms ([[MaskForms]]), as written and as the rewriter reads the
  *      stored text: none can answer with an error quoting the unmasked value.
  */
object TransformSqlValidator:

  sealed trait Result
  final case class Valid(canonical: String) extends Result
  final case class Invalid(reason: String)  extends Result

  private val MaxLen = 1024

  private val Denylist: Set[String] = Set(
    "read_csv",
    "read_csv_auto",
    "read_parquet",
    "read_json",
    "read_json_auto",
    "query",
    "sql",
    "attach",
    "detach",
    "install",
    "load",
    "system",
    "current_setting",
    "set_setting",
    "pg_read_server_files",
    "pg_read_binary_file"
  )

  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)

  def validate(transformSql: String, protectedColumn: String): Result =
    val trimmed = Option(transformSql).map(_.trim).getOrElse("")
    if trimmed.isEmpty then return Invalid("transformSql is empty")

    // Reject splice-unsafe constructs BEFORE parsing: jsqlparser accepts a `--`/`/*` comment or a
    // bare `;` and silently truncates at it rather than failing the parse, so the parse step below
    // proves nothing about these. ColumnPolicyRewriter splices the validated transformSql directly
    // into generated SQL, so an unrejected comment or statement separator here is the same
    // splice-safety hazard as the RLS predicate case (see [[RowPredicateValidator]]).
    if SqlCommentScan.hasComment(trimmed) then
      return Invalid("comments are not allowed in transformSql")
    if SqlCommentScan.hasSemicolon(trimmed) then
      return Invalid("statement separators (;) are not allowed in transformSql")

    // Read whole: JSqlParser's expression entry point answers the expression before a token it
    // cannot read (`c => 1` reads as `c`) and its lexer takes `//` for a comment, so a mask could
    // otherwise be stored shorter than written, the raw column included.
    val expr: Expression =
      WholeExpression.parseFor("transformSql", trimmed, cond = false) match
        case Right(e)     => e
        case Left(reason) => return Invalid(reason)

    val violations = scala.collection.mutable.ListBuffer.empty[String]
    // Every column reference of the expression, as the rewriter reads it when it splices the mask:
    // one walk for both, so a mask valid here is one the rewriter applies (a lambda's parameter is
    // no column; a time keyword or a node JSqlParser makes of a column reference is read as DuckDB
    // reads it).
    val stored = Try(ResidualColumns.parseStoredExpression(trimmed)) match
      case Success(e)                                          => e
      case Failure(e: ResidualColumns.StoredExpressionRefused) => return Invalid(e.reason)
      case Failure(ex) => return Invalid(s"transformSql does not parse: ${ex.getMessage}")
    val references                  = ResidualColumns.references(stored)
    def protectedName(name: String) =
      ai.starlake.sql.IdentifierFold.same(unquote(name), unquote(protectedColumn))
    def otherColumn(name: String) =
      s"transformSql references column '$name' but the policy is on '$protectedColumn'"
    references.columns.foreach { col =>
      if !protectedName(col.getColumnName) then violations += otherColumn(col.getColumnName)
    }
    references.timeKeys.foreach { tk =>
      SqlValueNames.reading(tk) match
        case SqlValueNames.Reading.Reference(name) =>
          if !protectedName(name) && SqlValueNames.functionCall(name).isEmpty then
            violations += otherColumn(name)
        case SqlValueNames.Reading.Aliased(name, _) => violations += otherColumn(name)
        case SqlValueNames.Reading.Call             => ()
    }
    references.opaque.foreach(reason => violations += s"transformSql holds $reason")
    references.unknown.foreach(cls => violations += s"transformSql holds a $cls node")
    walk(expr) {
      case _: Select =>
        // Select is the common supertype of PlainSelect, ParenthesedSelect, SetOperationList, etc.
        // Any SELECT inside an expression is a subquery - reject it.
        violations += "subqueries are not allowed in transformSql"
      case _: net.sf.jsqlparser.expression.operators.relational.ExistsExpression =>
        violations += "EXISTS subqueries are not allowed in transformSql"
      case fn: SqlFunction =>
        val name = Option(fn.getName).getOrElse("").toLowerCase(Locale.ROOT)
        if Denylist.contains(name) || name.startsWith("pragma_") then
          violations += s"function '$name' is not allowed in transformSql"
      case _ => ()
    }

    val parts = MaskForms.refusals(expr)
    if parts.nonEmpty then violations += notAllowed(parts)

    if violations.nonEmpty then return Invalid(violations.distinct.mkString("; "))

    val canonical = expr.toString
    if canonical.length > MaxLen then
      return Invalid(s"canonical transformSql exceeds $MaxLen chars (got ${canonical.length})")

    // What is stored is the canonical text, and what applies is the rewriter's reading of it
    // (ResidualColumns.parseStoredExpression): both must read whole and hold only allowed forms.
    val desugared = MisreadNames.stored(canonical)
    if desugared.refusal.isDefined then return Invalid(desugared.refusal.get)
    WholeExpression.parseFor("transformSql", desugared.text, cond = false) match
      case Left(reason) => return Invalid(reason)
      case Right(_)     => ()
    val applied = Try(ResidualColumns.parseStoredExpression(canonical)) match
      case Success(e) => e
      case Failure(_) => return Invalid("transformSql does not read back as stored")
    val appliedParts = MaskForms.refusals(applied)
    if appliedParts.nonEmpty then return Invalid(notAllowed(appliedParts))

    Valid(canonical)

  /** The refusal of a mask holding parts that are not allowed forms, each named, never the mask. */
  private def notAllowed(parts: List[String]): String =
    s"transformSql uses ${parts.take(5).mkString("; ")}, which is not an allowed mask form; " +
      s"a mask may use only ${MaskForms.Allowed}"

  /** The parts of a stored mask a create would now refuse as not allowed forms, for the boot
    * report: Some(parts), each a fixed phrase or a function name, never the mask's text, or
    * Some(Nil) when it is refused for another reason; None when a create would admit it.
    */
  def refusedParts(transformSql: String, protectedColumn: String): Option[List[String]] =
    validate(transformSql, protectedColumn) match
      case Valid(_)   => None
      case Invalid(_) =>
        val trimmed = Option(transformSql).map(_.trim).getOrElse("")
        Some(
          WholeExpression.parse(trimmed, cond = false) match
            case Right(e)     => MaskForms.refusals(e)
            case Left(reason) => List(reason)
        )

  /** Visits every node of `start`, at any depth: every child JSqlParser keeps
    * ([[StarExpansion.nodes]], the shared reflective walk), so a subscript's or a slice's indexes,
    * a COLLATE operand, a lambda's body, an IN list, a CASE operand, a call's ORDER BY or FILTER
    * and a subquery's own nodes are all seen.
    */
  private def walk(start: Expression)(visit: PartialFunction[Any, Unit]): Unit =
    StarExpansion.nodes(start).foreach(node => if visit.isDefinedAt(node) then visit(node))
