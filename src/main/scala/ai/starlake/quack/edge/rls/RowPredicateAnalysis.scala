package ai.starlake.quack.edge.rls

import ai.starlake.acl.parser.AstChildren
import ai.starlake.sql.IdentifierFold
import net.sf.jsqlparser.expression.{Expression, LambdaExpression}
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.select.PlainSelect
import scala.jdk.CollectionConverters.*

/** Best-effort column analysis for boot diagnostics only. DuckDB binds and enforces every runtime
  * row predicate on the node; this never rewrites a caller's SQL.
  */
object RowPredicateAnalysis:
  val UnqualifiableCause  = "unqualifiable_predicate"
  val PredicateErrorCause = "predicate_error"
  private final class UnqualifiablePredicate extends RuntimeException("unqualifiable predicate")
  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)

  private val KeywordFunctions: Set[String] = Set(
    "current_user",
    "user",
    "current_role",
    "session_user",
    "current_schema",
    "current_catalog",
    "localtime",
    "localtimestamp"
  )

  private def expressionColumns(
      expr: Expression,
      qualifiers: List[List[String]]
  ): Set[String] =
    val read                           = scala.collection.mutable.Set.empty[String]
    val visited: java.util.Set[AnyRef] =
      java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
      )
    def fold(s: String): String                        = IdentifierFold.fold(unquote(s))
    def qualify(c: Column, lambdas: Set[String]): Unit =
      val q       = List(c.getCatalogName, c.getSchemaName, c.getTableName).filter(_ != null)
      val name    = fold(c.getColumnName)
      val head    = q.headOption.map(fold)
      val keyword = !c.getColumnName.exists(ch => ch == '"' || ch == '`') &&
        KeywordFunctions.contains(IdentifierFold.fold(c.getColumnName))
      if q.isEmpty && (lambdas.contains(name) || keyword) then ()
      else if head.exists(lambdas.contains) then ()
      else
        val folded = q.map(fold)
        val own    = (3 to 1 by -1).find(k => q.size >= k && qualifiers.contains(folded.take(k)))
        val rest   = own.map(q.drop).getOrElse(q)
        if rest.size > 2 then throw new UnqualifiablePredicate
        read += rest.headOption.map(fold).getOrElse(name)

    def route(value: Any, lambdas: Set[String]): Unit =
      value match
        case null                         => ()
        case it: java.lang.Iterable[?]    => it.asScala.foreach(route(_, lambdas))
        case arr: Array[?]                => arr.foreach(route(_, lambdas))
        case e: java.util.Map.Entry[?, ?] =>
          route(e.getKey, lambdas); route(e.getValue, lambdas)
        case m: java.util.Map[?, ?]                => m.entrySet.asScala.foreach(route(_, lambdas))
        case n: AnyRef if AstChildren.isAstNode(n) => walk(n, lambdas)
        case leaf if AstChildren.isLeaf(leaf)      => ()
        case _                                     => throw new UnqualifiablePredicate
    def children(node: AnyRef, lambdas: Set[String]): Unit =
      AstChildren
        .accessors(node.getClass)
        .foreach(m => route(AstChildren.read(m, node), lambdas))
    def walk(node: AnyRef, lambdas: Set[String]): Unit =
      if node != null && visited.add(node) then
        node match
          case c: Column           => qualify(c, lambdas)
          case l: LambdaExpression =>
            val params = Option(l.getIdentifiers).map(_.asScala.toList).getOrElse(Nil).map(fold)
            walk(l.getExpression, lambdas ++ params)
          case _: PlainSelect => throw new UnqualifiablePredicate
          case _: Table       => throw new UnqualifiablePredicate
          case other          => children(other, lambdas)
    walk(expr, Set.empty)
    read.toSet

  def predicateColumns(
      predicateSql: String,
      catalog: String,
      schema: String,
      table: String
  ): Either[String, Set[String]] =
    def fold(x: String): String = IdentifierFold.fold(unquote(x))
    val qualifiers              =
      List(
        List(fold(table)),
        List(fold(schema), fold(table)),
        List(fold(catalog), fold(schema), fold(table))
      )
    try
      val one = RowPolicyIdentity.neutralize(predicateSql)
      if wholePredicate(one).isLeft then throw new IllegalArgumentException("not whole")
      val expr = CCJSqlParserUtil.parseCondExpression("(" + one + ")")
      Right(expressionColumns(expr, qualifiers) - "rowid")
    catch
      case _: UnqualifiablePredicate      => Left(UnqualifiableCause)
      case scala.util.control.NonFatal(_) => Left(PredicateErrorCause)

  private def wholePredicate(substituted: String): Either[String, Unit] =
    val stored = ai.starlake.quack.edge.cls.MisreadNames.stored(substituted)
    stored.refusal match
      case Some(reason) => Left(reason)
      case None         => WholeExpression.parse(stored.text, cond = true).map(_ => ())
