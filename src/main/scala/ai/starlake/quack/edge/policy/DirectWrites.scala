package ai.starlake.quack.edge.policy

import ai.starlake.acl.parser.AstChildren
import ai.starlake.sql.{IdentifierFold, QuotedIdentifier}
import net.sf.jsqlparser.expression.{Expression, Function}
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.{ConflictActionType, Insert, InsertConflictAlgorithm}
import net.sf.jsqlparser.statement.merge.{Merge, MergeInsert, MergeUpdate}
import net.sf.jsqlparser.statement.select.{
  AllColumns,
  AllTableColumns,
  FromItem,
  Join,
  LateralSubSelect,
  PlainSelect,
  Select,
  SelectItem,
  WithItem
}
import net.sf.jsqlparser.statement.truncate.Truncate
import net.sf.jsqlparser.statement.update.{Update, UpdateSet}

import scala.jdk.CollectionConverters._

/** What a write does with its own target table, for the protected-write guard: the row rewriter
  * never sees a write, so a write aimed at a row-policy table reaches every row, the hidden ones
  * included, and a write that reads a masked column of its target in a WHERE, a SET value, a
  * RETURNING or an ON CONFLICT clause reads it raw (owner ruling D27).
  *
  * Both readings fail closed: a shape they do not model counts as a read.
  */
private[policy] object DirectWrites:

  /** An INSERT that only adds the rows it names: no upsert (`ON CONFLICT DO UPDATE`,
    * `INSERT OR REPLACE`, `ON DUPLICATE KEY UPDATE`), no RETURNING or OUTPUT, no OVERWRITE. The
    * only write a row-policy holder may aim at a table their row policy covers.
    */
  def plainInsert(ast: Statement): Boolean =
    ast match
      case i: Insert =>
        val conflictUpdates = Option(i.getConflictAction).exists { a =>
          a.getConflictActionType != ConflictActionType.DO_NOTHING &&
          a.getConflictActionType != ConflictActionType.NOTHING
        }
        !conflictUpdates &&
        !Option(i.getConflictAlgorithm).contains(InsertConflictAlgorithm.OR_REPLACE) &&
        !i.isUseDuplicate &&
        nonEmpty(i.getDuplicateUpdateSets).isEmpty &&
        i.getDuplicateAction == null &&
        nonEmpty(i.getReturningClause).isEmpty &&
        i.getOutputClause == null &&
        !i.isOverwrite
      case _ => false

  /** The expressions of a write that DuckDB evaluates outside its source query and whose subqueries
    * the read extraction does not walk: RETURNING items, and an INSERT's ON CONFLICT target, its DO
    * UPDATE values and conditions, and its ON DUPLICATE KEY UPDATE values. Empty for any other
    * statement.
    */
  def clauseExpressions(ast: Statement): List[Expression] =
    def returning(items: java.util.List[? <: SelectItem[?]]): List[Expression] =
      nonEmpty(items).toList.flatMap(_.asScala).flatMap(si => Option(si.getExpression))
    def setValues(sets: java.util.List[UpdateSet]): List[Expression] =
      nonEmpty(sets).toList.flatMap(_.asScala).flatMap(us => Option(us.getValues).toList)
    ast match
      case i: Insert =>
        returning(i.getReturningClause) ++
          Option(i.getConflictTarget).toList.flatMap { t =>
            Option(t.getIndexExpression).toList ++ Option(t.getWhereExpression).toList
          } ++
          Option(i.getConflictAction).toList.flatMap { a =>
            setValues(a.getUpdateSets) ++ Option(a.getWhereExpression).toList
          } ++
          setValues(i.getDuplicateUpdateSets)
      case u: Update => returning(u.getReturningClause)
      case d: Delete => returning(d.getReturningClause)
      case m: Merge  => returning(m.getReturningClause)
      case _         => Nil

  /** The statement-level WITH items of a write, so a synthetic read of its clauses resolves a CTE
    * name as the CTE.
    */
  def withItems(ast: Statement): java.util.List[WithItem[?]] =
    ast match
      case i: Insert => i.getWithItemsList
      case u: Update => u.getWithItemsList
      case d: Delete => d.getWithItemsList
      case m: Merge  => m.getWithItemsList
      case _         => null

  /** One SELECT listing `exprs`, under `withs`, for the read extraction to walk. */
  def asSelect(exprs: List[Expression], withs: java.util.List[WithItem[?]]): Select =
    val ps = new PlainSelect()
    ps.setSelectItems(exprs.map(e => new SelectItem[Expression](e): SelectItem[?]).asJava)
    if withs != null && !withs.isEmpty then ps.setWithItemsList(withs)
    ps

  /** Whether `ast`, a write whose target carries column policies on `protectedColumns`, reads one
    * of those columns of its target: a column reference naming one (bare, qualified by the target
    * or by anything that is not another table of the statement, or as a struct field head), the
    * target's whole row, or a star or COLUMNS(...) over the target. The SET left sides and an
    * INSERT's column list are assignments, not reads, and an INSERT's source query is the read
    * extraction's to judge. TRUNCATE reads nothing; any other shape counts as a read.
    */
  def readsProtectedColumn(ast: Statement, protectedColumns: List[String]): Boolean =
    ast match
      case _: Truncate => false
      case i: Insert   =>
        val targets = targetNames(i.getTable)
        val scan    = new Scan(protectedColumns, targets, heads(i, targets) + "excluded")
        Option(i.getConflictTarget).foreach { t =>
          nonEmpty(t.getIndexColumnNames).foreach(_.asScala.foreach(scan.name))
          scan.walk(t.getIndexExpression)
          scan.walk(t.getWhereExpression)
          nonEmpty(t.getIndexElements).foreach(scan.walk)
        }
        Option(i.getConflictAction).foreach { a =>
          nonEmpty(a.getUpdateSets).foreach(_.asScala.foreach(us => scan.walk(us.getValues)))
          scan.walk(a.getWhereExpression)
        }
        nonEmpty(i.getDuplicateUpdateSets).foreach(_.asScala.foreach(us => scan.walk(us.getValues)))
        scan.walk(i.getReturningClause)
        scan.walk(i.getOutputClause)
        scan.hit
      case u: Update =>
        val targets = targetNames(u.getTable)
        val scan    = new Scan(protectedColumns, targets, heads(u, targets))
        scan.skipNode(u.getTable)
        nonEmpty(u.getUpdateSets).foreach(_.asScala.foreach(us => scan.skipAll(us.getColumns)))
        nonEmpty(u.getWithItemsList).foreach(scan.skipAll)
        scan.skipSource(u.getFromItem)
        scan.walk(u)
        scan.hit
      case d: Delete =>
        val targets = (Option(d.getTable).toList ++ nonEmpty(d.getTables).toList.flatMap(_.asScala))
          .flatMap(targetNames)
          .toSet
        val scan = new Scan(protectedColumns, targets, heads(d, targets))
        scan.skipNode(d.getTable)
        nonEmpty(d.getTables).foreach(scan.skipAll)
        nonEmpty(d.getWithItemsList).foreach(scan.skipAll)
        nonEmpty(d.getUsingFromItemList).foreach(_.asScala.foreach(scan.skipSource))
        scan.walk(d)
        scan.hit
      case m: Merge =>
        val targets = targetNames(m.getTable)
        val scan    = new Scan(protectedColumns, targets, heads(m, targets))
        scan.skipNode(m.getTable)
        nonEmpty(m.getWithItemsList).foreach(scan.skipAll)
        scan.skipSource(m.getFromItem)
        scan.skipNode(m.getUsingTable)
        nonEmpty(m.getOperations).foreach(_.asScala.foreach {
          case mu: MergeUpdate =>
            nonEmpty(mu.getUpdateSets).foreach(_.asScala.foreach(us => scan.skipAll(us.getColumns)))
          case mi: MergeInsert => scan.skipAll(mi.getColumns)
          case _               => ()
        })
        scan.walk(m)
        scan.hit
      case _ => true

  private def nonEmpty[A <: java.util.Collection[?]](c: A): Option[A] =
    Option(c).filterNot(_.isEmpty)

  private def unquote(s: String): String = QuotedIdentifier.unquote(s)

  /** The names a write's target answers to in its own statement: its table name and its alias. */
  private def targetNames(t: Table): Set[String] =
    Option(t).toSet.flatMap { tb =>
      Option(tb.getName).map(unquote).toSet ++
        Option(tb.getAlias).flatMap(a => Option(a.getName)).map(unquote)
    }

  /** The names that stand for another table of the statement: every FROM item's alias, and a base
    * table's own name, wherever it appears, the targets' names left out. A column qualified by one
    * of them reads that table, not the target.
    */
  private def heads(root: AnyRef, targets: Set[String]): Set[String] =
    val out                     = scala.collection.mutable.Set.empty[String]
    def add(fi: FromItem): Unit =
      if fi != null then
        Option(fi.getAlias).flatMap(a => Option(a.getName)).map(unquote).foreach(out += _)
        fi match
          case t: Table if t.getAlias == null => Option(t.getName).map(unquote).foreach(out += _)
          case _                              => ()
    root match
      case u: Update =>
        add(u.getFromItem)
      case d: Delete =>
        nonEmpty(d.getUsingFromItemList).foreach(_.asScala.foreach(add))
      case m: Merge =>
        add(m.getFromItem)
        add(m.getUsingTable)
        Option(m.getUsingAlias).flatMap(a => Option(a.getName)).map(unquote).foreach(out += _)
      case _ => ()
    nodes(root).foreach {
      case ps: PlainSelect =>
        add(ps.getFromItem)
        nonEmpty(ps.getJoins).foreach(_.asScala.foreach(j => add(j.getFromItem)))
      case j: Join => add(j.getFromItem)
      case _       => ()
    }
    out.filterNot(h => targets.exists(IdentifierFold.same(_, h))).toSet

  private def nodes(root: AnyRef): List[AnyRef] =
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    val out                    = scala.collection.mutable.ListBuffer.empty[AnyRef]
    def walk(value: Any): Unit =
      value match
        case null                         => ()
        case it: java.lang.Iterable[?]    => it.forEach(walk(_))
        case arr: Array[?]                => arr.foreach(walk)
        case e: java.util.Map.Entry[?, ?] => walk(e.getKey); walk(e.getValue)
        case m: java.util.Map[?, ?]       => m.entrySet.forEach(walk(_))
        case n: AnyRef if AstChildren.isAstNode(n) && seen.add(n) =>
          out += n
          AstChildren.accessors(n.getClass).foreach(m => walk(AstChildren.read(m, n)))
        case _ => ()
    walk(root)
    out.toList

  /** The reflective walk of one write's read positions. `hit` turns true on the first reference
    * that may read a protected column of the target, and on any value the walk cannot follow.
    */
  private final class Scan(
      protectedColumns: List[String],
      targets: Set[String],
      heads: Set[String]
  ):
    var hit: Boolean = false

    private val skipped = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    private val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )

    private def isProtected(n: String): Boolean =
      protectedColumns.exists(IdentifierFold.same(_, n))
    private def isTarget(n: String): Boolean = targets.exists(IdentifierFold.same(_, n))
    private def isHead(n: String): Boolean   = heads.exists(IdentifierFold.same(_, n))

    def skipNode(n: AnyRef): Unit = if n != null then skipped.add(n): Unit

    def skipAll(it: java.lang.Iterable[?]): Unit =
      if it != null then it.forEach { case a: AnyRef => skipNode(a); case _ => () }

    /** A source of the write (UPDATE FROM, DELETE USING, MERGE USING): its own reads are the read
      * extraction's, and only a LATERAL source can see the target, so only that one is walked.
      */
    def skipSource(fi: FromItem): Unit =
      fi match
        case _: LateralSubSelect => ()
        case null                => ()
        case other               => skipNode(other)

    /** A bare name in a column position, such as an ON CONFLICT target column. */
    def name(n: String): Unit = if n != null && isProtected(unquote(n)) then hit = true

    def walk(value: Any): Unit = route(value, nested = false)

    private def route(value: Any, nested: Boolean): Unit =
      if !hit then
        value match
          case null                         => ()
          case it: java.lang.Iterable[?]    => it.forEach(route(_, nested))
          case arr: Array[?]                => arr.foreach(route(_, nested))
          case e: java.util.Map.Entry[?, ?] => route(e.getKey, nested); route(e.getValue, nested)
          case m: java.util.Map[?, ?]       => m.entrySet.forEach(route(_, nested))
          case n: AnyRef if AstChildren.isAstNode(n) =>
            if !skipped.contains(n) && seen.add(n) then node(n, nested)
          case leaf if AstChildren.isLeaf(leaf) => ()
          case _                                => hit = true

    private def children(n: AnyRef, nested: Boolean): Unit =
      AstChildren.accessors(n.getClass).foreach(m => route(AstChildren.read(m, n), nested))

    private def node(n: AnyRef, nested: Boolean): Unit =
      n match
        case c: Column =>
          column(c)
          route(c.getArrayConstructor, nested)
        case a: AllTableColumns =>
          val t = Option(a.getTable).flatMap(t => Option(t.getName)).map(unquote)
          if t.forall(n => isTarget(n) || !isHead(n)) then hit = true
          children(a, nested)
        case a: AllColumns =>
          if !nested then hit = true
          children(a, nested)
        case f: Function =>
          val name = Option(f.getName).map(unquote).getOrElse("")
          if !nested && (f.isAllColumns || IdentifierFold.same(name, "columns") ||
              IdentifierFold.same(name, "*columns"))
          then hit = true
          children(f, nested)
        case j: Join =>
          // The joined item is a source like the FROM one; its ON and USING are read positions.
          j.getFromItem match
            case l: LateralSubSelect => route(l, nested = true)
            case _                   => ()
          route(j.getOnExpressions, nested)
          route(j.getUsingColumns, nested)
        case s: Select => children(s, nested = true)
        case other     => children(other, nested)

    /** A column reference reads the target's protected column unless it is qualified by another
      * table of the statement (whose name is neither the target's nor a protected column's); bare,
      * qualified by the target or by an unknown head (a struct field of the target), it does when
      * any of its parts is a protected column or when it names the target itself (its whole row).
      */
    private def column(c: Column): Unit =
      val qualifier =
        Option(c.getTable).toList.flatMap(t => Option(t.getNameParts).toList.flatMap(_.asScala))
      val parts =
        (qualifier.reverse ++ Option(c.getColumnName).toList).filter(_ != null).map(unquote)
      val other = parts.lengthCompare(2) >= 0 && {
        val head = parts.head
        isHead(head) && !isTarget(head) && !isProtected(head)
      }
      if !other && (parts.exists(isProtected) || parts.lastOption.exists(isTarget)) then hit = true
