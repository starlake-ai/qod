package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.cls.MaskTypeReport.Binding
import ai.starlake.quack.edge.rls.StaleRowPolicyReport.Database
import ai.starlake.quack.ondemand.api.CatalogColumnEntry
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacRole, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Using

/** [[MaskTypeReport]]: a stored mask whose result does not have its column's type (which qod-node
  * refuses on every read of the column) is reported once per policy and database, naming both types
  * and never the mask; masks that fit, and policies the report cannot check, are not.
  */
class MaskTypeReportSpec extends AnyFlatSpec with Matchers:

  private val role = RbacRole("r1", "acme", "analyst")
  private val db   = Database("acme", "lake", "acme_lake")

  /** The table `main.emp`, with the DuckLake types its columns are stored with. */
  private val columns = List(
    "salary" -> "int32",
    "email"  -> "varchar",
    "born"   -> "date",
    "amount" -> "decimal(10,2)",
    "score"  -> "float64",
    "tags"   -> "list"
  ).zipWithIndex.map { case ((n, t), i) => CatalogColumnEntry(i, n, t, true, false) }

  private def mask(column: String, sql: String, id: String = "p1", table: String = "emp") =
    RoleColumnPolicy(
      id,
      role.id,
      "*",
      "main",
      table,
      column,
      RoleColumnPolicy.ActionMask,
      Some(sql)
    )

  private def linesFor(policies: RoleColumnPolicy*): List[String] =
    Using.resource(MaskTypeReport.binder()) { conn =>
      MaskTypeReport.lines(
        List(role),
        policies.toList,
        List(db),
        (d, s, t) => if d == db && s == "main" && t == "emp" then columns else Nil,
        (m, c, ty) => MaskTypeReport.bind(conn, m, c, ty)
      )
    }

  "duckDbType" should "map DuckLake's scalar types and leave the others out" in {
    MaskTypeReport.duckDbType("int32") shouldBe Some("INTEGER")
    MaskTypeReport.duckDbType("uint128") shouldBe Some("UHUGEINT")
    MaskTypeReport.duckDbType("float64") shouldBe Some("DOUBLE")
    MaskTypeReport.duckDbType("decimal(38,0)") shouldBe Some("DECIMAL(38,0)")
    MaskTypeReport.duckDbType("timestamptz") shouldBe Some("TIMESTAMPTZ")
    List("struct", "list", "map", "variant", "geometry", "decimal(x,2)", "").foreach(t =>
      MaskTypeReport.duckDbType(t) shouldBe None
    )
  }

  "lines" should "report a mask returning another type than its column's, naming both" in {
    val out = linesFor(mask("salary", "round(TRY_CAST(salary AS DOUBLE) / 1000) * 1000"))
    out should have size 1
    out.head should startWith(
      "WARN: tenant 'acme' role 'analyst': column policy p1 on acme_lake.main.emp.salary"
    )
    out.head should include("its mask returns DOUBLE, but the column is INTEGER")
    out.head should include("TRY_CAST(<a DOUBLE, text or constant form> AS INTEGER)")
    out.head should not include "round("
  }

  it should "report hash() over a text column and a text mask over a date" in {
    val out = linesFor(mask("email", "hash(email)", "p1"), mask("born", "'***'", "p2"))
    out should have size 2
    out.exists(l =>
      l.contains("p1") && l.contains("returns UBIGINT, but the column is VARCHAR")
    ) shouldBe true
    out.exists(l =>
      l.contains("p2") && l.contains("returns VARCHAR, but the column is DATE")
    ) shouldBe true
  }

  it should "say nothing of masks that keep their column's type" in {
    linesFor(
      mask("salary", "TRY_CAST(round(TRY_CAST(salary AS DOUBLE) / 1000) * 1000 AS INTEGER)", "a"),
      mask("salary", "NULL", "b"),
      mask("salary", "0", "c"),
      mask("email", "left(email, 2) || '***'", "d"),
      mask("email", "md5(CAST(email AS VARCHAR))", "e"),
      mask("born", "TRY_CAST('1900-01-01' AS DATE)", "f"),
      mask("amount", "TRY_CAST(round(TRY_CAST(amount AS DOUBLE)) AS DECIMAL(10, 2))", "g"),
      mask("score", "round(TRY_CAST(score AS DOUBLE), -1)", "h"),
      mask("Salary", "TRY_CAST(0 AS INTEGER)", "i")
    ) shouldBe Nil
  }

  it should "leave out what it cannot check" in {
    linesFor(
      mask("tags", "'***'", "nested"),
      mask("missing", "'***'", "no-column"),
      mask("salary", "'***'", "no-table", table = "other"),
      mask("salary", "'***'", "wildcard", table = "*"),
      // A mask a create would refuse is reported by StoredMaskReport, never bound here.
      mask("email", "md5(email)", "refused"),
      RoleColumnPolicy("deny", role.id, "*", "main", "emp", "salary", "deny", None),
      mask("salary", "'***'", "other-catalog").copy(catalogName = "elsewhere")
    ) shouldBe Nil
  }

  it should "report a mask that does not bind over its column's type" in {
    val out = MaskTypeReport.lines(
      List(role),
      List(mask("salary", "'***'")),
      List(db),
      (_, _, _) => columns,
      (_, _, _) => Some(Binding.Unbound)
    )
    out should have size 1
    out.head should include("its mask cannot be bound over a column of type INTEGER")
  }

  "bind" should "bind as the node does, over a NULL of the column's type" in
    Using.resource(MaskTypeReport.binder()) { conn =>
      MaskTypeReport.bind(conn, "upper(email)", "email", "VARCHAR") shouldBe
        Some(Binding.Types("VARCHAR", "VARCHAR"))
      MaskTypeReport.bind(conn, "nosuchfn(email)", "email", "VARCHAR") shouldBe
        Some(Binding.Unbound)
      MaskTypeReport.bind(conn, "'x'", "c", "NOT A TYPE") shouldBe None
    }

  "binder" should "reach no file" in
    Using.resource(MaskTypeReport.binder()) { conn =>
      MaskTypeReport.bind(
        conn,
        "(SELECT count(*) FROM read_text('/etc/hosts'))",
        "c",
        "VARCHAR"
      ) shouldBe
        Some(Binding.Unbound)
    }

  private def refusalFor(p: RoleColumnPolicy, tenant: String = "acme"): Option[String] =
    Using.resource(MaskTypeReport.binder()) { conn =>
      MaskTypeReport.refusal(
        tenant,
        p,
        List(db),
        (d, s, t) => if d == db && s == "main" && t == "emp" then columns else Nil,
        (m, c, ty) => MaskTypeReport.bind(conn, m, c, ty)
      )
    }

  "refusal" should "refuse a mask whose type is not its column's, naming the place and both types" in {
    val out = refusalFor(mask("salary", "TRY_CAST(salary AS DOUBLE) * 10"))
    out.get should startWith(
      "invalid transformSql: on acme_lake.main.emp.salary its mask returns DOUBLE"
    )
    out.get should include("but the column is INTEGER")
    out.get should include("TRY_CAST(<a DOUBLE, text or constant form> AS INTEGER)")
  }

  it should "pass a mask that fits, and a write it cannot judge" in {
    refusalFor(mask("salary", "TRY_CAST(TRY_CAST(salary AS DOUBLE) * 10 AS INTEGER)")) shouldBe None
    refusalFor(mask("salary", "'***'", table = "not_there")) shouldBe None
    refusalFor(mask("tags", "'***'")) shouldBe None
    refusalFor(mask("salary", "'***'"), tenant = "globex") shouldBe None
  }

  "createColumnPolicy and updateColumnPolicy" should "refuse a mask its column's type refuses" in {
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    val t     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val r     = sup.createRole(t.id, "analyst").unsafeRunSync().toOption.get
    sup.maskTypeRefusal = (tenant, p) => refusalFor(p.copy(roleId = role.id), tenant)
    def create(sql: String) =
      sup.createColumnPolicy(r.id, "*", "main", "emp", "salary", "mask", Some(sql)).unsafeRunSync()

    create("TRY_CAST(salary AS DOUBLE) * 10") match
      case Left(err) => err.toString should include("but the column is INTEGER")
      case Right(p)  => fail(s"created $p")
    store.listColumnPolicies(r.id) shouldBe Nil

    val fitting = "TRY_CAST(TRY_CAST(salary AS DOUBLE) * 10 AS INTEGER)"
    val created = create(fitting).toOption.get
    sup.updateColumnPolicy(created.id, "mask", Some("'***'")).unsafeRunSync() match
      case Left(err) => err.toString should include("returns VARCHAR, but the column is INTEGER")
      case Right(_)  => fail("updated to a VARCHAR mask")
    store.listColumnPolicies(r.id).flatMap(_.transformSql) shouldBe List(fitting)
    sup.updateColumnPolicy(created.id, "deny", None).unsafeRunSync() shouldBe Right(())
  }
