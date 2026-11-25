package ai.starlake.quack.model

/** Discriminator for [[FederatedSource]]. `Sql` is the original free-form form, where the operator
  * writes the INSTALL / CREATE SECRET / ATTACH themselves. The built-in typed ones are declared
  * through typed fields, and QoD renders the SQL: `IcebergRest` through
  * `ai.starlake.quack.ondemand.federation.iceberg.IcebergSetupSql`, `Postgres` and `Mysql` through
  * `ai.starlake.quack.ondemand.federation.rdbms.RdbmsSetupSql`. `Plugin` types are contributed by a
  * module's `FederatedSourceProvider`, which renders their SQL.
  *
  * This enum lives in `model` (a leaf package that must not import `ondemand`), which is also why
  * [[FederatedSource.config]] is raw JSON text rather than a typed config object, and why the
  * registered plugin wires are held here as plain names.
  */
enum FederatedSourceType(val wire: String):
  case Sql                  extends FederatedSourceType("sql")
  case IcebergRest          extends FederatedSourceType("iceberg_rest")
  case Postgres             extends FederatedSourceType("postgres")
  case Mysql                extends FederatedSourceType("mysql")
  case Plugin(name: String) extends FederatedSourceType(name)

  /** True when QoD renders this source's ATTACH from typed config, so the rendered SQL is QoD's
    * own: the attach verifier re-issues it, and `readOnly` reaches the engine as `READ_ONLY`.
    */
  def rendered: Boolean = this != Sql

  /** True for the relational database types whose config is
    * `ai.starlake.quack.ondemand.federation.rdbms.RdbmsConfig`.
    */
  def rdbms: Boolean = this == Postgres || this == Mysql

object FederatedSourceType:
  val builtIns: List[FederatedSourceType] = List(Sql, IcebergRest, Postgres, Mysql)

  @volatile private var plugins: Option[Set[String]] = None

  /** Set once at boot from the loaded modules' providers. A second call with the same set is a
    * no-op (tests); a different set throws.
    */
  def registerPluginWires(wires: Set[String]): Unit = synchronized {
    val lower = wires.map(_.toLowerCase(java.util.Locale.ROOT))
    plugins match
      case None                     => plugins = Some(lower)
      case Some(set) if set == lower => ()
      case Some(_) => throw new IllegalStateException("plugin source types are already registered")
  }

  def pluginWires: Set[String] = plugins.getOrElse(Set.empty)

  def fromWire(s: String): Option[FederatedSourceType] =
    val t = Option(s).getOrElse("").trim
    builtIns.find(_.wire.equalsIgnoreCase(t)).orElse {
      val lower = t.toLowerCase(java.util.Locale.ROOT)
      Option.when(pluginWires.contains(lower))(Plugin(lower))
    }

  /** Every wire value, for error messages that list the accepted ones. */
  def wireList: String = (builtIns.map(_.wire) ++ pluginWires.toList.sorted).mkString(", ")

  /** Lenient read path: an unknown or NULL `source_type` column reads as `Sql`, which is what every
    * pre-0038 row is, EXCEPT a non-blank value naming no built-in type: that is a plugin source whose
    * module is not loaded, read as `Plugin` so it is never rendered as operator SQL.
    */
  def fromWireOrSql(s: String): FederatedSourceType =
    fromWire(s).getOrElse {
      val t = Option(s).map(_.trim.toLowerCase(java.util.Locale.ROOT)).getOrElse("")
      if t.isEmpty then Sql else Plugin(t)
    }
