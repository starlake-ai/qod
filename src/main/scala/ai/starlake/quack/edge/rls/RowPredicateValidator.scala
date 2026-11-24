package ai.starlake.quack.edge.rls

import java.util.Locale
import net.sf.jsqlparser.expression.Expression

/** Strict-safety validator for free-form row-filter predicates on row policies. Runs once at policy
  * create/update time. Pure (no I/O, no DuckDB connection).
  *
  * A `predicate_sql` value must be ONE boolean DuckDB expression that:
  *   1. Parses whole as one condition ([[WholeExpression]]) once identity-substitution tokens
  *      (`${user}`, `${tenant}`, `${tenantId}`, `${groups}`, `${roles}`) are neutralised.
  *   2. Contains no subqueries (`SELECT`) or `EXISTS` clauses.
  *   3. Calls no side-effect or escape functions (denylist + `pragma_*` prefix).
  *   4. Is bounded in size (<= 1024 chars).
  *
  * Unlike [[ai.starlake.quack.edge.cls.TransformSqlValidator]] the predicate may reference ANY
  * column (it filters rows of the protected table), so there is no single-column containment check.
  * The canonical form returned keeps the `${...}` tokens intact - they are substituted by
  * [[RowPolicyIdentity]] before signing the session handle, not here.
  *
  * Detection works on the PARSED, canonicalised expression with string literals stripped, rather
  * than a node-by-node AST walk: a function or sub-`SELECT` nested inside any wrapper node (e.g.
  * `read_csv(...) IS NOT NULL`) is still caught, and stripping literals first avoids flagging a
  * harmless value like `note = 'select one'`.
  */
object RowPredicateValidator:

  sealed trait Result
  final case class Valid(canonical: String) extends Result
  final case class Invalid(reason: String)  extends Result

  private val MaxLen = 1024

  /** Same escape/side-effect denylist as the CLS transform validator. */
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

  private val TokenRegex = "\\$\\{[a-zA-Z]+\\}".r
  // Single-quoted SQL string literal, honouring doubled-quote escapes: 'O''Brien'.
  private val StringLiteral = "'(?:[^']|'')*'".r

  def validate(predicateSql: String): Result =
    val trimmed = Option(predicateSql).map(_.trim).getOrElse("")
    if trimmed.isEmpty then return Invalid("predicateSql is empty")
    if trimmed.length > MaxLen then
      return Invalid(s"predicateSql exceeds $MaxLen chars (got ${trimmed.length})")

    // Preserve the administrative expression contract: reject comments and statement separators
    // before parsing, because JSqlParser may silently validate only their leading fragment.
    if SqlCommentScan.hasComment(trimmed) then
      return Invalid("comments are not allowed in predicateSql")
    if SqlCommentScan.hasSemicolon(trimmed) then
      return Invalid("statement separators (;) are not allowed in predicateSql")

    // Neutralise identity tokens before parsing: `${user}` -> NULL so both bare (`u = ${user}`)
    // and list (`g IN (${groups})`) forms parse. Tokens inside string literals collapse to the
    // literal 'NULL', which is harmless for the parse check.
    val probe = TokenRegex.replaceAllIn(trimmed, "NULL")

    // Normalize stored-expression syntax for administrative validation (MisreadNames.stored).
    val read = ai.starlake.quack.edge.cls.MisreadNames.stored(probe)
    if read.refusal.isDefined then return Invalid(read.refusal.get)
    // Read whole: JSqlParser's condition entry point answers the condition before a token it
    // cannot read, and its lexer takes `//` (DuckDB's integer division) for a comment. The text is
    // stored as written, so validate all of it. Runtime DuckDB parsing and binding remain an
    // independent check on the native node.
    val expr: Expression =
      WholeExpression.parseFor("predicateSql", read.text, cond = true) match
        case Right(e)     => e
        case Left(reason) => return Invalid(reason)

    // Scan the canonical, literal-stripped form. Lower-cased so keyword/function matching is
    // case-insensitive; literals removed so their contents can never trip a keyword check.
    val scan = StringLiteral.replaceAllIn(expr.toString, "''").toLowerCase(Locale.ROOT)

    if "(?<![a-z0-9_])(?:select|exists)(?![a-z0-9_])".r.findFirstIn(scan).isDefined then
      return Invalid("subqueries are not allowed in predicateSql")

    val hitDeny = Denylist.find(fn => s"(?<![a-z0-9_])$fn\\s*\\(".r.findFirstIn(scan).isDefined)
    hitDeny match
      case Some(fn) => return Invalid(s"function '$fn' is not allowed in predicateSql")
      case None     => ()
    if "(?<![a-z0-9_])pragma_[a-z0-9_]*\\s*\\(".r.findFirstIn(scan).isDefined then
      return Invalid("pragma_* functions are not allowed in predicateSql")

    // Keep the original (tokens intact) as the canonical stored form.
    Valid(trimmed)
