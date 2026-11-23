package ai.starlake.quack.edge.policy

import ai.starlake.acl.parser.AstChildren
import ai.starlake.quack.edge.cls.PositionalReferences
import ai.starlake.sql.{IdentifierFold, QuotedIdentifier}
import net.sf.jsqlparser.expression.{Expression, Function}
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
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

  /** DDL that leaves an existing table of that name as it is: a CREATE TABLE or CREATE VIEW with no
    * OR REPLACE (DuckDB refuses it, or does nothing under IF NOT EXISTS, when the name is taken).
    * Any other DDL aimed at a table the principal's policies cover is refused (owner ruling D29).
    */
  def createsWithoutReplacing(ast: Statement): Boolean =
    def replaces(options: java.util.List[String]): Boolean =
      Option(options).exists(_.asScala.exists(o => o != null && o.toUpperCase.contains("REPLACE")))
    ast match
      case ct: CreateTable => !ct.isOrReplace && !replaces(ct.getCreateOptionsStrings)
      case cv: CreateView  => !cv.isOrReplace
      case _               => false

  /** Whether `ast`, a write whose target carries column policies on `protectedColumns`, may read
    * one of those columns of its target. A reference counts as such a read whenever DuckDB may bind
    * it there:
    *   - a bare name that is a protected column, or the target itself (its whole row);
    *   - `a.c` where `a` is a protected column (a struct field of it), `c` names the target (a
    *     schema- or catalog-qualified whole row), or `a` names the target and `c` is protected;
    *   - three parts or more where any part is a protected column or the last one names the target
    *     (DuckDB tries `catalog.schema.table.column` and `schema.table.column` before an alias,
    *     whatever alias the statement declares);
    *   - a positional `#n` at the write's own level, which DuckDB numbers over the target's columns
    *     in RETURNING (and an upsert's clauses), and over the target and the write's sources
    *     together elsewhere: it counts unless the write has no other source (or it sits in
    *     RETURNING or ON CONFLICT) and `targetColumns` names its column, unprotected; inside a
    *     subquery it numbers that subquery's own FROM, and counts when there is none;
    *   - a top-level star, a `t.*` over the target or a protected struct, or `COLUMNS(...)`.
    * The SET left sides and an INSERT's column list are assignments, not reads, and an INSERT's
    * source query is the read extraction's to judge. TRUNCATE reads nothing; any other shape, a
    * value the walk cannot follow, or a `#n` in the text the parse did not keep as a column counts
    * as a read.
    */
  def readsProtectedColumn(
      ast: Statement,
      protectedColumns: List[String],
      targetColumns: () => List[String]
  ): Boolean =
    ast match
      case _: Truncate => false
      case i: Insert   =>
        val scan = new Scan(protectedColumns, targetNames(i.getTable), targetColumns, true)
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
        scan.hit || unkeptPositional(ast)
      case u: Update =>
        val sources = u.getFromItem != null || nonEmpty(u.getJoins).isDefined ||
          nonEmpty(u.getStartJoins).isDefined
        val scan = new Scan(protectedColumns, targetNames(u.getTable), targetColumns, !sources)
        scan.skipNode(u.getTable)
        nonEmpty(u.getUpdateSets).foreach(_.asScala.foreach(us => scan.skipAll(us.getColumns)))
        nonEmpty(u.getWithItemsList).foreach(scan.skipAll)
        scan.skipSource(u.getFromItem)
        scan.returning(u.getReturningClause)
        scan.walk(u)
        scan.hit || unkeptPositional(ast)
      case d: Delete =>
        val targets = (Option(d.getTable).toList ++ nonEmpty(d.getTables).toList.flatMap(_.asScala))
          .flatMap(targetNames)
          .toSet
        val sources = nonEmpty(d.getUsingFromItemList).isDefined ||
          nonEmpty(d.getJoins).isDefined || nonEmpty(d.getUsingJoins).isDefined
        val scan = new Scan(protectedColumns, targets, targetColumns, !sources)
        scan.skipNode(d.getTable)
        nonEmpty(d.getTables).foreach(scan.skipAll)
        nonEmpty(d.getWithItemsList).foreach(scan.skipAll)
        nonEmpty(d.getUsingFromItemList).foreach(_.asScala.foreach(scan.skipSource))
        scan.returning(d.getReturningClause)
        scan.walk(d)
        scan.hit || unkeptPositional(ast)
      case m: Merge =>
        val scan = new Scan(protectedColumns, targetNames(m.getTable), targetColumns, false)
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
        scan.returning(m.getReturningClause)
        scan.walk(m)
        scan.hit || unkeptPositional(ast)
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

  /** A `#n` in the statement's text that the parse did not keep as a column reference: a position
    * the walk cannot judge, so it counts as a read.
    */
  private def unkeptPositional(ast: Statement): Boolean =
    val kept = nodes(ast).count {
      case c: Column => Option(c.getColumnName).flatMap(PositionalReferences.position).isDefined
      case _         => false
    }
    PositionalReferences.textualCount(ast.toString) > kept

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

  /** Where a reference sits: at the write's own level (`returning` inside its RETURNING), or in a
    * subquery, which has a FROM or not.
    */
  private enum Level:
    case Write(returning: Boolean)
    case Subquery(hasFrom: Boolean)

  /** The reflective walk of one write's read positions. `hit` turns true on the first reference
    * that may read a protected column of the target, and on any value the walk cannot follow.
    * `numbersTargetOnly`: outside RETURNING, the write's own `#n` numbers the target's columns
    * alone (no other source).
    */
  private final class Scan(
      protectedColumns: List[String],
      targets: Set[String],
      targetColumns: () => List[String],
      numbersTargetOnly: Boolean
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

    /** The write's RETURNING, judged now with its own numbering, and left out of the later walk. */
    def returning(rc: AnyRef): Unit =
      if rc != null then
        route(rc, Level.Write(returning = true))
        skipNode(rc)

    /** A bare name in a column position, such as an ON CONFLICT target column. */
    def name(n: String): Unit = if n != null && isProtected(unquote(n)) then hit = true

    def walk(value: Any): Unit = route(value, Level.Write(returning = false))

    private def route(value: Any, level: Level): Unit =
      if !hit then
        value match
          case null                             => ()
          case n: AnyRef if skipped.contains(n) => ()
          case it: java.lang.Iterable[?]        => it.forEach(route(_, level))
          case arr: Array[?]                    => arr.foreach(route(_, level))
          case e: java.util.Map.Entry[?, ?]     => route(e.getKey, level); route(e.getValue, level)
          case m: java.util.Map[?, ?]           => m.entrySet.forEach(route(_, level))
          case n: AnyRef if AstChildren.isAstNode(n) => if seen.add(n) then node(n, level)
          case leaf if AstChildren.isLeaf(leaf)      => ()
          case _                                     => hit = true

    private def children(n: AnyRef, level: Level): Unit =
      AstChildren.accessors(n.getClass).foreach(m => route(AstChildren.read(m, n), level))

    private def topLevel(level: Level): Boolean = level.isInstanceOf[Level.Write]

    private def node(n: AnyRef, level: Level): Unit =
      n match
        case c: Column =>
          column(c, level)
          route(c.getArrayConstructor, level)
        case a: AllTableColumns =>
          val parts =
            Option(a.getTable).toList.flatMap(t => Option(t.getNameParts).toList.flatMap(_.asScala))
          val named = parts.filter(_ != null).map(unquote)
          if named.isEmpty || named.exists(isProtected) || named.exists(isTarget) then hit = true
          children(a, level)
        case a: AllColumns =>
          if topLevel(level) then hit = true
          children(a, level)
        case f: Function =>
          val name = Option(f.getName).map(unquote).getOrElse("")
          if topLevel(level) && (f.isAllColumns || IdentifierFold.same(name, "columns") ||
              IdentifierFold.same(name, "*columns"))
          then hit = true
          children(f, level)
        case j: Join =>
          // The joined item is a source like the FROM one; its ON and USING are read positions.
          j.getFromItem match
            case l: LateralSubSelect => route(l, Level.Subquery(hasFrom = true))
            case _                   => ()
          route(j.getOnExpressions, level)
          route(j.getUsingColumns, level)
        case ps: PlainSelect => children(ps, Level.Subquery(hasFrom = ps.getFromItem != null))
        case s: Select       =>
          val inner = level match
            case sub: Level.Subquery => sub
            case _                   => Level.Subquery(hasFrom = false)
          children(s, inner)
        case other => children(other, level)

    /** Whether `c` may read a protected column of the target (see `readsProtectedColumn`). */
    private def column(c: Column, level: Level): Unit =
      val qualifier =
        Option(c.getTable).toList.flatMap(t => Option(t.getNameParts).toList.flatMap(_.asScala))
      val parts =
        (qualifier.reverse ++ Option(c.getColumnName).toList).filter(_ != null).map(unquote)
      val positional =
        if qualifier.isEmpty then Option(c.getColumnName).flatMap(PositionalReferences.position)
        else None
      positional match
        case Some(n) => if positionalReads(n, level) then hit = true
        case None    =>
          val reads = parts match
            case Nil           => true
            case List(one)     => isProtected(one) || isTarget(one)
            case List(head, c) =>
              isProtected(head) || isTarget(c) || (isTarget(head) && isProtected(c))
            case many => many.exists(isProtected) || isTarget(many.last)
          if reads then hit = true

    private def positionalReads(n: Int, level: Level): Boolean =
      level match
        case Level.Subquery(hasFrom)  => !hasFrom
        case Level.Write(inReturning) =>
          if !(inReturning || numbersTargetOnly) then true
          else
            val cols = targetColumns()
            n < 1 || n > cols.size || isProtected(cols(n - 1))
