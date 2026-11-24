package ai.starlake.quack.edge.rls

import java.util.Locale
import ai.starlake.acl.parser.AstChildren
import ai.starlake.quack.edge.authz.{FunctionClass, FunctionClasses}
import net.sf.jsqlparser.expression.{AnalyticExpression, Function as SqlFunction, LambdaExpression}
import net.sf.jsqlparser.statement.select.Select
import scala.jdk.CollectionConverters.*
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
    if RowPolicyIdentity.hasUnknownToken(trimmed) then
      return Invalid("unknown identity token in predicateSql")
    if !RowPolicyIdentity.safeTokenLocations(trimmed) then
      return Invalid("identity tokens must be complete SQL values outside literals and identifiers")
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

    nativeShape(expr) match
      case Some(reason) => Invalid(reason)
      case None         => Valid(trimmed)

  private def nativeShape(expr: Expression): Option[String] =
    val visited = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    var failure: Option[String] = None
    def route(value: Any): Unit = value match
      case null                         => ()
      case i: java.lang.Iterable[?]     => i.asScala.foreach(route)
      case a: Array[?]                  => a.foreach(route)
      case e: java.util.Map.Entry[?, ?] => route(e.getKey); route(e.getValue)
      case m: java.util.Map[?, ?]       => m.entrySet.asScala.foreach(route)
      case n: AnyRef if AstChildren.isAstNode(n) && visited.add(n) =>
        n match
          case _: Select           => failure = Some("subqueries are not allowed in predicateSql")
          case _: LambdaExpression =>
            failure = Some("lambdas are not allowed in native row predicates")
          case _: AnalyticExpression =>
            failure = Some("window functions are not allowed in native row predicates")
          case f: SqlFunction =>
            val parts = Option(f.getMultipartName)
              .map(_.asScala.toList)
              .getOrElse(Nil)
              .map(ai.starlake.sql.QuotedIdentifier.unquote)
            val name    = parts.lastOption.getOrElse("").toLowerCase(Locale.ROOT)
            val builtin =
              parts.size == 1 || parts.dropRight(1).map(_.toLowerCase(Locale.ROOT)) == List(
                "system",
                "main"
              )
            val allowed = List("scalar", "macro").exists(t =>
              FunctionClasses.of("system", "main", name, t).contains(FunctionClass.Allowed)
            )
            if !builtin || !allowed || Set(
                "getvariable",
                "current_setting",
                "random",
                "uuid",
                "uuidv4",
                "uuidv7",
                "nextval",
                "currval"
              )(name)
            then failure = Some("function is not allowed in native row predicates")
          case _ => ()
        if failure.isEmpty then
          AstChildren.accessors(n.getClass).foreach(m => route(AstChildren.read(m, n)))
      case n: AnyRef if AstChildren.isAstNode(n) => ()
      case leaf if AstChildren.isLeaf(leaf)      => ()
      case _ => failure = Some("unsupported native row predicate expression")
    route(expr)
    failure
