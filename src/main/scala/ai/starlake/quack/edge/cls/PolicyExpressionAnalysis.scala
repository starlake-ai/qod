package ai.starlake.quack.edge.cls

import ai.starlake.acl.parser.AstChildren
import net.sf.jsqlparser.expression.TimeKeyExpression
import net.sf.jsqlparser.schema.{Column, Table}
import scala.jdk.CollectionConverters._

/** Conservative expression analysis for stored masks and protected-write checks. Source-table
  * resolution, policy precedence and residual rewrite checks now belong to the native engine.
  */
private[quack] object PolicyExpressionAnalysis:
  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)
  private def lower(s: String): String   = ai.starlake.sql.IdentifierFold.fold(s)

  final case class References(
      columns: List[Column],
      timeKeys: List[TimeKeyExpression],
      opaque: List[String],
      unknown: Option[String]
  )

  private final case class Scan(
      references: References,
      calls: List[AnyRef],
      analyticMethods: List[net.sf.jsqlparser.expression.AnalyticExpression],
      dateUnits: List[java.util.List[Any]]
  )

  private def scan(root: AnyRef): Scan =
    val visited = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    val columns  = scala.collection.mutable.ListBuffer.empty[Column]
    val timeKeys = scala.collection.mutable.ListBuffer.empty[TimeKeyExpression]
    val opaque   = scala.collection.mutable.ListBuffer.empty[String]
    val calls    = scala.collection.mutable.ListBuffer.empty[AnyRef]
    val analytic =
      scala.collection.mutable.ListBuffer.empty[net.sf.jsqlparser.expression.AnalyticExpression]
    val dateUnits = scala.collection.mutable.ListBuffer.empty[java.util.List[Any]]
    var unknown   = Option.empty[String]

    def walk(node: AnyRef, lambdas: Set[String]): Unit =
      if visited.add(node) then
        MisreadNames.opaqueReading(node).foreach(opaque += _)
        MisreadNames.maskOnlyReading(node).foreach(opaque += _)
        if MisreadNames.parsedArguments(node).isDefined then calls += node
        node match
          case c: Column
              if c.getTable == null && lambdas.contains(lower(unquote(c.getColumnName))) =>
            ()
          case c: Column             => columns += c
          case tk: TimeKeyExpression => timeKeys += tk
          case a: net.sf.jsqlparser.expression.AnalyticExpression
              if Option(a.getName).exists(_.contains('.')) =>
            analytic += a
          case _ => ()
        val inner = node match
          case l: net.sf.jsqlparser.expression.LambdaExpression =>
            lambdas ++ Option(l.getIdentifiers).toList
              .flatMap(_.asScala)
              .map(n => lower(unquote(n)))
          case _ => lambdas
        AstChildren.accessors(node.getClass).foreach { method =>
          AstChildren.read(method, node) match
            case _: Table if node.isInstanceOf[Column] => ()
            case value                                 => route(value, inner)
        }

    def route(value: Any, lambdas: Set[String]): Unit = value match
      case null                      => ()
      case it: java.lang.Iterable[?] =>
        it match
          case l: java.util.List[?]
              if l.stream.anyMatch(
                _.isInstanceOf[net.sf.jsqlparser.expression.DateUnitExpression]
              ) =>
            dateUnits += l.asInstanceOf[java.util.List[Any]]
          case _ => ()
        it.forEach(route(_, lambdas))
      case arr: Array[?]                => arr.foreach(route(_, lambdas))
      case e: java.util.Map.Entry[?, ?] => route(e.getKey, lambdas); route(e.getValue, lambdas)
      case m: java.util.Map[?, ?]       => m.entrySet.forEach(route(_, lambdas))
      case n: AnyRef if AstChildren.isAstNode(n) => walk(n, lambdas)
      case leaf if AstChildren.isLeaf(leaf)      => ()
      case other => unknown = unknown.orElse(Some(other.getClass.getName))

    walk(root, Set.empty)
    Scan(
      References(columns.toList, timeKeys.toList, opaque.toList, unknown),
      calls.toList,
      analytic.toList,
      dateUnits.toList
    )

  def references(root: AnyRef): References = scan(root).references

  /** Some(refusal) when the parse of `text` (`roots`, its statements) kept fewer arguments for a
    * call than the text gives it ([[MisreadNames.droppedArgument]]).
    */
  def droppedArgument(text: String, roots: List[AnyRef]): Option[String] =
    MisreadNames.droppedArgument(text, roots.flatMap(r => scan(r).calls))

  /** The head of an analytic call's dotted name (`ssn` of `ssn.max`), None for a function qualifier
    * DuckDB resolves to its own functions ([[MethodCalls.isQualifier]]).
    */
  private def methodName(
      a: net.sf.jsqlparser.expression.AnalyticExpression
  ): (MethodCalls.CallName, List[String]) =
    val parts = a.getName.split('.').toList
    MethodCalls.callName(parts) -> parts.init.map(p => lower(unquote(p)))

  /** Some(refusal) when `root` holds an aggregate, window or ordered-set call in method form
    * (`ssn.max() FILTER (..)`, JSqlParser's analytic call with a dotted name and no column node)
    * whose head is no function qualifier DuckDB resolves ([[MethodCalls.isQualifier]]): with no
    * scopes at hand (the protected-write guard) any such head may be a column or table binding.
    */
  def methodCallLeft(root: AnyRef): Option[String] =
    scan(root).analyticMethods.collectFirst {
      case a if methodName(a)._1 != MethodCalls.CallName.Plain =>
        MethodCalls.Refusal(s"${a.getName}(..)")
    }

  /** Rewrites, in place, each date part of `root` in an expression list (`count(year)`), which
    * DuckDB reads as a column, into that column's quoted reference. True when one was rewritten.
    */
  def bindDateUnits(root: AnyRef): Boolean =
    scan(root).dateUnits.foldLeft(false) { (changed, list) =>
      val it  = list.listIterator()
      var any = changed
      while it.hasNext do
        it.next() match
          case du: net.sf.jsqlparser.expression.DateUnitExpression =>
            it.set(new Column("\"" + du.toString.trim + "\""))
            any = true
          case _ => ()
      any
    }

  /** A stored mask or policy expression parsed as DuckDB reads it: the words of
    * [[MisreadNames.quoteWords]] quoted and its date parts made columns ([[bindDateUnits]]). It is
    * read whole ([[ai.starlake.quack.edge.rls.WholeExpression]], as the validator reads it): a text
    * the parser reads only in part (an imported mask is stored unchecked) is refused, never applied
    * as the part it read.
    */
  def parseStoredExpression(text: String): net.sf.jsqlparser.expression.Expression =
    val desugared = MisreadNames.stored(text)
    desugared.refusal.foreach(reason => throw new StoredExpressionRefused(reason))
    val expr = ai.starlake.quack.edge.rls.WholeExpression.parse(desugared.text, cond = false) match
      case Right(e)     => e
      case Left(reason) =>
        throw new StoredExpressionRefused(s"its text holds $reason, so it does not read whole")
    bindDateUnits(expr): Unit
    expr

  /** A stored expression holding a method call [[MethodCalls.desugarStored]] cannot write. */
  final class StoredExpressionRefused(val reason: String) extends RuntimeException(reason)
