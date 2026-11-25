package ai.starlake.quack.edge.authz

import java.util.Locale

/** What a non-superuser may do with a function the node resolved (spec Section 6). */
enum FunctionClass:
  /** No access of its own: what it reads is judged as the plan's objects. */
  case Allowed

  /** One of the catalog listings the quack client's ATTACH sync reads (`duckdb_tables`,
    * `duckdb_views`, `duckdb_schemas`, `duckdb_columns`). Admitted like Allowed; until part 2
    * filters them in the plan, the gateway's metadata filter narrows their rows to the principal's
    * grants.
    */
  case Listing

  /** A table function whose arguments name files or URLs: admitted only when every location passes
    * the tenant's location rules (decisions 15 and 18).
    */
  case File

  /** Superusers only, whatever the grants. */
  case Superuser

object FunctionClass:
  /** The class a table cell names: `allowed`, `listing`, `file` or `superuser`. */
  def parse(cell: String): Option[FunctionClass] =
    FunctionClass.values.find(_.toString.toLowerCase(Locale.ROOT) == cell)

/** The class of every function a node resolves, generated from qod-node's own listing
  * (`native/qod-node/functions.json`) into the bundled `authz/function-classes.tsv` by
  * scripts/dev/gen-qod-node-classification.sh. FunctionClassesSpec fails when the two differ, so a
  * DuckDB upgrade cannot land with a function nobody classified. A function the table lacks has no
  * class, and the decision pipeline refuses it ("function <name> is not classified").
  */
object FunctionClasses:

  val Resource: String = "authz/function-classes.tsv"

  /** The function types descriptions carry. */
  val Types: Set[String] = Set("scalar", "aggregate", "window", "table", "pragma")

  /** DuckDB's built-in macros and table macros, classed from their bodies (Task 19) for qod-node's
    * parse screen, which judges them in bind-time slots: DuckDB expands a macro before any plan
    * exists, so no description ever carries one.
    */
  val MacroTypes: Set[String] = Set("macro", "table_macro")

  /** A function as the table keys it, lowercased: DuckDB resolves names case-insensitively. */
  final case class Key(schema: String, name: String, tpe: String)

  /** Every classified function. Read on first use; throws when the bundled table is missing or
    * malformed, which FunctionClassesSpec rules out.
    */
  lazy val all: Map[Key, FunctionClass] = parse(ClassificationTables.read(Resource))

  /** The class of function `name` of type `tpe` that DuckDB resolved in `catalog`.`schema`, or
    * None. Built-in and extension functions live in the `system` catalog; a function of any other
    * catalog (a macro, which DuckDB expands before a plan exists anyway) has no class. A function
    * DuckDB bound with no catalog entry at all (a COPY's reader or writer, which the describer
    * reports with catalog and schema "") is looked up as `system`.`main`, where every such built-in
    * is listed.
    */
  def of(catalog: String, schema: String, name: String, tpe: String): Option[FunctionClass] =
    val (cat, sch) =
      if catalog.isEmpty && schema.isEmpty then ("system", "main") else (catalog, schema)
    if lower(cat) != "system" then None
    else
      val key = Key(lower(sch), lower(name), lower(tpe))
      all.get(key).orElse(extra.get(key))

  @volatile private var extra: Map[Key, FunctionClass] = Map.empty

  private val ExtraTypes: Set[String] = Set("scalar", "aggregate", "table")

  /** Checks the classes a module gives the functions its nodes link: type scalar, aggregate or
    * table, class allowed or listing, and a name no built-in carries under any type.
    */
  def validateExtra(
      rows: List[(String, String, String)]
  ): Either[String, Map[Key, FunctionClass]] =
    rows.foldLeft[Either[String, Map[Key, FunctionClass]]](Right(Map.empty)) {
      case (Left(e), _)                   => Left(e)
      case (Right(acc), (name, tpe, cls)) =>
        val key = Key("main", lower(name), lower(tpe))
        FunctionClass.parse(cls) match
          case None                            => Left(s"$name: unknown class $cls")
          case Some(_) if !ExtraTypes(key.tpe) => Left(s"$name: unsupported type $tpe")
          case Some(FunctionClass.Superuser) | Some(FunctionClass.File) =>
            Left(s"$name: a module function cannot be classed $cls")
          case Some(_) if all.keys.exists(_.name == key.name) || acc.contains(key) =>
            Left(s"$name is already classified")
          case Some(c) => Right(acc + (key -> c))
    }

  /** Installs the module classes for this JVM. Installing the same rows again is a no-op; a
    * different set after a non-empty install is refused so two callers cannot overwrite each other.
    */
  def installExtra(rows: List[(String, String, String)]): Either[String, Unit] =
    validateExtra(rows).flatMap { m =>
      synchronized {
        if m.isEmpty || m == extra then Right(())
        else if extra.nonEmpty then Left("module function classes are already installed")
        else
          extra = m
          Right(())
      }
    }

  // As DuckDB folds a name it looks up (ASCII letters only): the Kelvin sign is no `k` here.
  private def lower(s: String): String = ai.starlake.sql.IdentifierFold.fold(s)

  /** The rows (`name`, `schema`, `type`, `class`) of `text`. Throws IllegalArgumentException naming
    * the line of an unknown type or class, a File class on anything but a table function, or a
    * function classified twice.
    */
  private[authz] def parse(text: String): Map[Key, FunctionClass] =
    ClassificationTables.rows(text, columns = 4).foldLeft(Map.empty[Key, FunctionClass]) {
      case (acc, (line, cells)) =>
        val key = Key(schema = cells(1), name = cells(0), tpe = cells(2))
        FunctionClass.parse(cells(3)) match
          case None =>
            throw new IllegalArgumentException(s"line $line: unknown class ${cells(3)}")
          case Some(_) if !Types(key.tpe) && !MacroTypes(key.tpe) =>
            throw new IllegalArgumentException(s"line $line: unknown function type ${key.tpe}")
          case Some(FunctionClass.File) if key.tpe != "table" =>
            throw new IllegalArgumentException(s"line $line: only a table function can be file")
          case Some(_) if acc.contains(key) =>
            throw new IllegalArgumentException(s"line $line: ${key.name} is classified twice")
          case Some(cls) => acc + (key -> cls)
    }
