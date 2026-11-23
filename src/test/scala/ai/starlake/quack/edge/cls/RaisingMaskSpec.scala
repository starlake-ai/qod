package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A new mask may not use a form DuckDB can answer with an error quoting the unmasked value
  *: a cast, a text parse, a JSON read, a date or time constructor, overflowing
  * arithmetic, a pattern or a date part read from the row, a comparison casting text to another
  * type. The non-raising forms (`TRY_CAST`, `try_strptime`, anything inside `TRY(...)`) are
  * admitted, and so is a form that can fail only on a constant. A stored mask keeps applying and is
  * reported at boot.
  */
class RaisingMaskSpec extends AnyFlatSpec with Matchers:
  import TransformSqlValidator._

  /** Each raising form, written over the column `c`. */
  private val Raising = List(
    "CAST(c AS INTEGER)",
    "c::INTEGER",
    "CAST(c AS DATE)",
    "CAST(c AS DECIMAL(4,2))",
    "CAST(c AS JSON)",
    "CAST(c AS UUID)",
    "CAST(c AS BLOB)",
    "left(CAST(c AS INTEGER), 2)",
    "strptime(c, '%Y-%m-%d')",
    "system.main.strptime(c, '%Y')",
    "json_extract(c, '$.a')",
    "json_extract_string(c, '$.a')",
    "json_value(c, '$.a')",
    "json_keys(c)",
    "json_type(c)",
    "json(c)",
    "from_json(c, '{\"a\":\"INT\"}')",
    "c ->> '$.a'",
    "c -> 'a'",
    "from_base64(c)",
    "unhex(c)",
    "unbin(c)",
    "decode(c)",
    "error(c)",
    "error('x')",
    "make_date(c, 1, 1)",
    "make_timestamp(c)",
    "to_timestamp(c)",
    "epoch_ms(c)",
    "to_days(c)",
    "date_add(c, INTERVAL 1 DAY)",
    "chr(c)",
    "repeat(c, 3)",
    "abs(c)",
    "factorial(c)",
    "c + 1",
    "c - 1",
    "c * 1000",
    "-c",
    "c << 2",
    "c % c",
    "c % 0",
    "c + INTERVAL 1 DAY",
    "INTERVAL (c) DAY",
    "regexp_matches('x', c)",
    "regexp_replace('x', c, 'y')",
    "regexp_extract('x', c)",
    "'x' ~ c",
    "'x' SIMILAR TO c",
    "date_part(c, DATE '2020-01-01')",
    "timezone(c, TIMESTAMP '2020-01-01 00:00:00')",
    "c = 1",
    "c <> 1",
    "c = 1.5",
    "c = true",
    "c = DATE '2020-01-01'",
    "c = current_date",
    "lower(c) = 1",
    "c IS DISTINCT FROM 1",
    "c IN (1, 2)",
    "c BETWEEN 1 AND 2",
    "c IS TRUE",
    "nullif(c, 1)",
    "CASE c WHEN 1 THEN 'a' END",
    "CASE WHEN c = 1 THEN 'a' ELSE 'b' END",
    "list_transform(l, x -> CAST(x AS INTEGER))"
  )

  /** Forms that raise on no value of the row, or answer NULL instead. */
  private val Safe = List(
    "'***'",
    "NULL",
    "md5(c)",
    "concat('user_', md5(c))",
    "left(c, 2) || '***'",
    "TRY_CAST(c AS INTEGER)",
    "TRY_CAST(c AS DATE)",
    "try_strptime(c, '%Y')",
    "TRY(CAST(c AS INTEGER))",
    "TRY(strptime(c, '%Y'))",
    "TRY(json_extract(c, '$.a'))",
    "TRY(c * 1000)",
    "TRY(c = 1)",
    "CAST(c AS VARCHAR)",
    "CAST(c AS TEXT)",
    "c::VARCHAR",
    "CAST('1' AS INTEGER)",
    "c || CAST(42 AS INTEGER)",
    "json_valid(c)",
    "regexp_replace(c, '\\d', 'X')",
    "regexp_matches(c, '^a')",
    "c ~ '^a'",
    "c SIMILAR TO 'a.*'",
    "c LIKE 'a%'",
    "date_trunc('month', c)",
    "date_part('year', c)",
    "strftime(c, '%Y')",
    "timezone('UTC', c)",
    "c / 1000",
    "c % 10",
    "round(c, -3)",
    "c = 'x'",
    "c IN ('a', 'b')",
    "c IS NULL",
    "length(c) > 3",
    "CASE WHEN length(c) > 3 THEN 'long' ELSE 'short' END",
    "CASE WHEN c = 'a' THEN 1 ELSE 2 END",
    "CASE c WHEN 'a' THEN 1 END",
    "list_filter(l, x -> len(x) < 3)",
    "hash(c)",
    "sha256(c)",
    "substr(c, 1, 3)",
    "lpad(c, 3, '*')"
  )

  private def column(mask: String): String = if mask.contains("(l,") then "l" else "c"

  "validate" should "refuse every form that can raise on a value, naming the safe forms" in
    Raising.foreach { mask =>
      withClue(mask) {
        validate(mask, column(mask)) match
          case Invalid(reason) =>
            reason should include("can raise an error quoting the unmasked value")
            reason should include("TRY_CAST")
            reason should include("try_strptime")
            reason should include("TRY(...)")
          case other => fail(s"$mask: expected Invalid, got $other")
      }
    }

  it should "admit the forms that cannot raise on a value" in
    Safe.foreach(mask => withClue(mask)(validate(mask, column(mask)) shouldBe a[Valid]))

  it should "refuse a mask the parser reads only in part" in {
    val r = validate("c // 1000", "c")
    r shouldBe a[Invalid]
    validate("c // 1000 * 1000", "c") shouldBe a[Invalid]
  }

  private def supervisor() =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    val t     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val role  = sup.createRole(t.id, "analyst").unsafeRunSync().toOption.get
    (store, sup, role)

  "createColumnPolicy and updateColumnPolicy" should "refuse a raising mask and keep a safe one" in {
    val (store, sup, role) = supervisor()
    Raising.filter(column(_) == "c").foreach { mask =>
      withClue(mask) {
        sup
          .createColumnPolicy(role.id, "*", "main", "t", "c", "mask", Some(mask))
          .unsafeRunSync()
          .left
          .map(_.toString) match
          case Left(reason) => reason should include("TRY(...)")
          case Right(p)     => fail(s"created $p")
      }
    }
    store.listColumnPolicies(role.id) shouldBe Nil
    val created = sup
      .createColumnPolicy(role.id, "*", "main", "t", "c", "mask", Some("TRY_CAST(c AS INTEGER)"))
      .unsafeRunSync()
      .toOption
      .get
    Raising.filter(column(_) == "c").foreach { mask =>
      withClue(mask) {
        sup.updateColumnPolicy(created.id, "mask", Some(mask)).unsafeRunSync() match
          case Left(err) => err.toString should include("TRY(...)")
          case Right(_)  => fail(s"updated to $mask")
      }
    }
    store.listColumnPolicies(role.id).flatMap(_.transformSql) shouldBe
      List("TRY_CAST(c AS INTEGER)")
    sup.updateColumnPolicy(created.id, "mask", Some("TRY(c * 1000)")).unsafeRunSync() shouldBe
      Right(())
  }

  "the boot report" should "name each stored raising mask once, never its text" in {
    val (store, _, role) = supervisor()
    val stored           = Raising.filter(column(_) == "c").zipWithIndex.map { (mask, i) =>
      store.insertColumnPolicy(
        RoleColumnPolicy(s"cp-$i", role.id, "*", "main", s"t$i", "c", "mask", Some(mask))
      )
    }
    store.insertColumnPolicy(
      RoleColumnPolicy("cp-safe", role.id, "*", "main", "ok", "c", "mask", Some("md5(c)"))
    )
    store.insertColumnPolicy(
      RoleColumnPolicy("cp-deny", role.id, "*", "main", "ok", "d", "deny", None)
    )
    val lines = StoredMaskReport.linesOf(store)
    lines should have size stored.size.toLong
    stored.foreach { p =>
      val mine = lines.filter(_.contains(s"column policy ${p.id} on"))
      withClue(p.transformSql)(mine should have size 1)
      mine.head should startWith("WARN: tenant 'acme' role 'analyst': ")
      mine.head should include(s"*.main.${p.tableName}.c")
      mine.head should include("can raise an error quoting the unmasked value")
      // never the mask's text, nor any literal of it
      mine.head should not include p.transformSql.get
    }
    lines.exists(_.contains("cp-safe")) shouldBe false
  }
