package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A new mask may use only the allowed forms ([[MaskForms]]): those DuckDB 2.0.0 answers, over a
  * value of any type the column may have, without an error quoting the value. Anything else is
  * refused at create and at update, the refusal naming the part that is not allowed (never the
  * mask) and the allowed forms. A stored mask keeps applying and is reported at boot.
  */
class MaskAllowlistSpec extends AnyFlatSpec with Matchers:
  import TransformSqlValidator._

  private val Allowed = MaskFormCases.Allowed

  /** Masks a create refuses, each with the words its refusal names the part by. Among them every
    * form the review found DuckDB answering with an error quoting the value, under TRY(...)
    * included, and the forms the evidence shows a VARIANT or BIGNUM column turns into one.
    */
  private val Refused = List(
    "md5(c)"                                        -> "md5() of a value that is not text",
    "sha256(c)"                                     -> "sha256() of a value that is not text",
    "length(c)"                                     -> "length() of a value that is not text",
    "sha1(c)"                                       -> "sha1() of a value that is not text",
    "md5(c[1])"                                     -> "md5() of a value that is not text",
    "length(upper(c)[1])"                           -> "length() of a value that is not text",
    "left(c, length(c::VARCHAR))"                   -> "not an integer constant",
    "upper(c, 'x')"                                 -> "another number of arguments",
    "left(c::VARCHAR, length(c::VARCHAR))"          -> "not an integer constant",
    "lpad(c::VARCHAR, 5, c::VARCHAR)"               -> "not a string constant",
    "regexp_replace(c::VARCHAR, c::VARCHAR, 'y')"   -> "not a string constant",
    "ltrim(c, c::VARCHAR)"                          -> "not a string constant",
    "rtrim(c, 1)"                                   -> "not a string constant",
    "ltrim(c, 'x', 'y')"                            -> "another number of arguments",
    "substring(c, length(c::VARCHAR))"              -> "not an integer constant",
    "substring(c, 1, 1.5)"                          -> "not an integer constant",
    "substring(c, '1')"                             -> "not an integer constant",
    "substring(c)"                                  -> "another number of arguments",
    "c / 1000"                                      -> "/ of a value not known to be a number",
    "round(c, -3)"                                  -> "round() of a value that is not a DOUBLE",
    "floor(c)"                                      -> "floor() of a value that is not a DOUBLE",
    "c % 10"                                        -> "% of a value not known to be a number",
    "TRY_CAST(c AS DOUBLE) % -1"                    -> "% by anything but a positive integer",
    "hash(c) % 0"                                   -> "% by anything but a positive integer",
    "TRY_CAST(c AS DOUBLE) / 0"                     -> "numeric constant other than 0",
    "TRY_CAST(c AS DOUBLE) / TRY_CAST(c AS DOUBLE)" -> "numeric constant other than 0",
    "c + 1"                                         -> "+, - or * outside DOUBLE arithmetic",
    "c * 1000"                                      -> "+, - or * outside DOUBLE arithmetic",
    "length(c::VARCHAR) + 1"                        -> "+, - or * outside DOUBLE arithmetic",
    "-c"                                            -> "a minus sign outside DOUBLE arithmetic",
    "CAST(c AS INTEGER)"                            -> "a cast other than",
    "c::INTEGER"                                    -> "a cast other than",
    "TRY_CAST(c AS INTEGER)"                        -> "a cast other than",
    // TRY_CAST of a BIGNUM (or a VARIANT, LIST, STRUCT or MAP holding one) to an integer, HUGEINT
    // or MAP type raises an INTERNAL error on DuckDB 2.0.0 and invalidates the database.
    "TRY_CAST(c AS BIGINT)"                -> "a cast other than",
    "TRY_CAST(c AS HUGEINT)"               -> "a cast other than",
    "TRY_CAST(c AS UBIGINT)"               -> "a cast other than",
    "TRY_CAST(c AS TINYINT)"               -> "a cast other than",
    "TRY_CAST(c AS MAP(VARCHAR, INTEGER))" -> "a cast other than",
    "TRY_CAST(c AS INTEGER[])"             -> "a cast other than",
    "TRY_CAST(c AS STRUCT(k INTEGER))"     -> "a cast other than",
    "TRY(TRY_CAST(c AS HUGEINT))"          -> "function 'try'",
    // Other spellings of an integer target, and DOUBLE PRECISION, which the allowlist does not
    // know: refused too, though DuckDB 2.0.0 answers them over a BIGNUM (NULL, or the value).
    "TRY_CAST(TRY_CAST(c AS DOUBLE) AS BIGINT)"      -> "a cast other than",
    "TRY_CAST(c AS DOUBLE PRECISION)"                -> "a cast other than",
    "TRY(CAST(c AS BIGINT))"                         -> "function 'try'",
    "CAST(c AS DOUBLE)"                              -> "a cast other than",
    "CAST(c AS VARCHAR[])"                           -> "a cast other than",
    "c::VARCHAR[]"                                   -> "a cast other than",
    "CAST(c AS JSON)"                                -> "a cast other than",
    "coalesce(c, 0)"                                 -> "a numeric constant among the values",
    "coalesce(c, 1.5)"                               -> "a numeric constant among the values",
    "CASE WHEN c IS NULL THEN -1 ELSE c END"         -> "a numeric constant among the values",
    "coalesce(c, md5(CAST(c AS VARCHAR)))"           -> "mixing values that are not all text",
    "coalesce(c || '*', c)"                          -> "mixing values that are not all text",
    "nullif(c, 1)"                                   -> "a second argument other than",
    "nullif(c, md5(c::VARCHAR))"                     -> "a second argument other than",
    "c || 1"                                         -> "a numeric constant beside ||",
    "CASE c WHEN 1 THEN 'a' END"                     -> "CASE <expression> WHEN",
    "CASE WHEN c = 'a' THEN 'x' END"                 -> "a CASE condition other than",
    "CASE WHEN c THEN 'a' ELSE 'b' END"              -> "a CASE condition other than",
    "NOT c"                                          -> "boolean operator",
    "c AND true"                                     -> "boolean operator",
    "c OR false"                                     -> "boolean operator",
    "if(c, 'a', 'b')"                                -> "function 'if'",
    "TIMESTAMP '2020-01-01 00:00:00' AT TIME ZONE c" -> "AT TIME ZONE",
    "TRY(c IN (1, 2))"                               -> "function 'try'",
    "TRY(timezone(c, TIMESTAMP '2020-01-01'))"       -> "function 'try'",
    "TRY(make_timestamptz(2020, 1, 1, 0, 0, 0, c))"  -> "function 'try'",
    "TRY(CAST(c AS INTEGER))"                        -> "function 'try'",
    "TRY(c * 1000)"                                  -> "function 'try'",
    "timezone(c, TIMESTAMP '2020-01-01')"            -> "function 'timezone'",
    "list_resize([1], c)"                            -> "function 'list_resize'",
    "get_bit(c, 1)"                                  -> "function 'get_bit'",
    "set_bit(c, 1, 1)"                               -> "function 'set_bit'",
    "c = 1"                                          -> "comparison",
    "c IN (1, 2)"                                    -> "IN list",
    "c BETWEEN 1 AND 2"                              -> "BETWEEN",
    "c LIKE 'a%'"                                    -> "LIKE",
    "c IS NULL"                                      -> "IS [NOT] NULL outside a CASE",
    "strptime(c, '%Y')"                              -> "function 'strptime'",
    "json_extract(c, '$.a')"                         -> "function 'json_extract'",
    "from_base64(c)"                                 -> "function 'from_base64'",
    "error('x')"                                     -> "function 'error'",
    "\"md5\"(CAST(c AS VARCHAR))"                    -> "written other than name(arguments)",
    "main.md5(CAST(c AS VARCHAR))"                   -> "written other than name(arguments)",
    "md5(DISTINCT CAST(c AS VARCHAR))"               -> "written other than name(arguments)",
    "E'x' || c"                                      -> "a string constant with a prefix",
    "$$x$$ || c"                                     -> "a string constant with a prefix",
    "current_date"                                   -> "time keyword",
    "INTERVAL 1 DAY"                                 -> "INTERVAL",
    "TRIM(BOTH 'x' FROM CAST(c AS VARCHAR))"         -> "TRIM(BOTH",
    "c ->> '$.a'"                                    -> "expression",
    // A subscript, a slice or a nested access is judged with the mask: constant indexes only.
    "c[CAST(c AS INTEGER)]"                        -> "a subscript or slice other than",
    "c[c::INTEGER]"                                -> "a subscript or slice other than",
    "c[error(c::VARCHAR)]"                         -> "a subscript or slice other than",
    "c[1:strpos(c, '@')]"                          -> "a subscript or slice other than",
    "c[json_extract_string(c, '$')]"               -> "a subscript or slice other than",
    "c[strptime(c::VARCHAR, '%Y')]"                -> "a subscript or slice other than",
    "c[1 / 0]"                                     -> "a subscript or slice other than",
    "c[(c = 1)::INTEGER]"                          -> "a subscript or slice other than",
    "c[getvariable('v')]"                          -> "a subscript or slice other than",
    "c[read_csv('x')]"                             -> "a subscript or slice other than",
    "c[query('select 1')]"                         -> "a subscript or slice other than",
    "c[1:2:c::INTEGER]"                            -> "a subscript or slice other than",
    "c[1.5]"                                       -> "a subscript or slice other than",
    "c[NULL]"                                      -> "a subscript or slice other than",
    "c[c::VARCHAR]"                                -> "a subscript or slice other than",
    "c['a':'b']"                                   -> "a subscript or slice other than",
    "c[1][c::INTEGER]"                             -> "a subscript or slice other than",
    "upper(c)[CAST(c AS INTEGER)]"                 -> "a subscript or slice other than",
    "(CAST(c AS INTEGER))[1]"                      -> "a cast other than",
    "c[1].x"                                       -> "row get expression",
    "list_transform([c], x -> CAST(x AS INTEGER))" -> "function 'list_transform'",
    "md5(CAST(c AS VARCHAR) ORDER BY c)"           -> "written other than name(arguments)",
    "count(c) FILTER (WHERE c IS NULL)"            -> "window or aggregate clause",
    "upper(c) IN ('A')"                            -> "IN list"
  )

  "validate" should "admit every allowed form" in
    Allowed.foreach(mask => withClue(mask)(validate(mask, "c") shouldBe a[Valid]))

  it should "refuse every other form, naming the part and the allowed forms, never the mask" in
    Refused.foreach { (mask, part) =>
      withClue(mask) {
        validate(mask, "c") match
          case Invalid(reason) =>
            reason should include(part)
            reason should include("not an allowed mask form")
            reason should include("a mask may use only")
            reason should not include mask
          case other => fail(s"expected Invalid, got $other")
      }
    }

  /** A denylisted call or a subquery in every child kind the validator's walk must descend into. */
  private val HiddenInChildren = List(
    "c[read_csv('x')]",
    "c[1:read_csv('x')]",
    "c[1:2:read_csv('x')]",
    "c[1][read_csv('x')]",
    "upper(c)[read_csv('x')]",
    "list_transform([c], x -> read_csv('x'))",
    "c IN (read_csv('x'))",
    "CASE read_csv('x') WHEN 1 THEN c END",
    "string_agg(c, ',' ORDER BY read_csv('x'))",
    "count(c) FILTER (WHERE read_csv('x') IS NULL)",
    "md5((read_csv('x')))",
    "[c, read_csv('x')]",
    "{'a': read_csv('x')}"
  )

  it should "see a denylisted call or a subquery in every child of the mask" in {
    HiddenInChildren.foreach { mask =>
      withClue(mask) {
        validate(mask, "c") match
          case Invalid(reason) => reason should include("function 'read_csv' is not allowed")
          case other           => fail(s"expected Invalid, got $other")
      }
    }
    validate("c[(SELECT 1)]", "c") match
      case Invalid(reason) => reason should include("subqueries are not allowed")
      case other           => fail(s"expected Invalid, got $other")
  }

  /** The forms the first round refused for raising: still refused, by the allowlist now. */
  private val FirstRound = List(
    "CAST(c AS DATE)",
    "CAST(c AS DECIMAL(4,2))",
    "CAST(c AS UUID)",
    "CAST(c AS BLOB)",
    "json_extract_string(c, '$.a')",
    "json_value(c, '$.a')",
    "json(c)",
    "unhex(c)",
    "decode(c)",
    "make_date(c, 1, 1)",
    "to_timestamp(c)",
    "epoch_ms(c)",
    "to_days(c)",
    "date_add(c, INTERVAL 1 DAY)",
    "chr(c)",
    "repeat(c, 3)",
    "abs(c)",
    "factorial(c)",
    "c << 2",
    "c % c",
    "c + INTERVAL 1 DAY",
    "regexp_matches('x', c)",
    "'x' ~ c",
    "date_part(c, DATE '2020-01-01')",
    "c = 1.5",
    "c = true",
    "c = current_date",
    "lower(c) = 1",
    "c IS DISTINCT FROM 1",
    "c IS TRUE",
    "list_transform(l, x -> CAST(x AS INTEGER))"
  )

  it should "refuse every form the first round refused" in
    FirstRound.foreach { mask =>
      val col = if mask.contains("(l,") then "l" else "c"
      withClue(mask)(validate(mask, col) shouldBe a[Invalid])
    }

  /** SQL's `SUBSTRING(<value> FROM <start> FOR <length>)` spelling: a bound read from the row is
    * judged like any other argument, whatever spelling holds it.
    */
  it should "judge SUBSTRING ... FROM ... FOR like substring's arguments" in {
    validate("SUBSTRING(c FROM CAST(c AS INTEGER) FOR 2)", "c") shouldBe a[Invalid]
    validate("SUBSTRING(c FROM 2 FOR CAST(c AS INTEGER))", "c") shouldBe a[Invalid]
    validate("SUBSTRING(c FROM 2 FOR 3)", "c") match
      case Valid(_)        => ()
      case Invalid(reason) => reason should include("a mask may use only")
  }

  private def supervisor() =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    val t     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val role  = sup.createRole(t.id, "analyst").unsafeRunSync().toOption.get
    (store, sup, role)

  "createColumnPolicy and updateColumnPolicy" should "refuse every refused form, naming it" in {
    val (store, sup, role) = supervisor()
    Refused.foreach { (mask, part) =>
      withClue(mask) {
        sup
          .createColumnPolicy(role.id, "*", "main", "t", "c", "mask", Some(mask))
          .unsafeRunSync() match
          case Left(err) =>
            err.toString should include(part)
            err.toString should include("a mask may use only")
          case Right(p) => fail(s"created $p")
      }
    }
    store.listColumnPolicies(role.id) shouldBe Nil
    val created = sup
      .createColumnPolicy(role.id, "*", "main", "t", "c", "mask", Some("md5(CAST(c AS VARCHAR))"))
      .unsafeRunSync()
      .toOption
      .get
    Refused.foreach { (mask, part) =>
      withClue(mask) {
        sup.updateColumnPolicy(created.id, "mask", Some(mask)).unsafeRunSync() match
          case Left(err) => err.toString should include(part)
          case Right(_)  => fail(s"updated to $mask")
      }
    }
    store.listColumnPolicies(role.id).flatMap(_.transformSql) shouldBe
      List("md5(CAST(c AS VARCHAR))")
    sup
      .updateColumnPolicy(created.id, "mask", Some("round(TRY_CAST(c AS DOUBLE) / 1000) * 1000"))
      .unsafeRunSync() shouldBe Right(())
  }

  "the boot report" should "name each stored refused mask once with its part, never its text" in {
    val (store, _, role) = supervisor()
    val stored           = Refused.zipWithIndex.map { case ((mask, _), i) =>
      store.insertColumnPolicy(
        RoleColumnPolicy(s"cp-$i", role.id, "*", "main", s"t$i", "c", "mask", Some(mask))
      )
    }
    store.insertColumnPolicy(
      RoleColumnPolicy(
        "cp-safe",
        role.id,
        "*",
        "main",
        "ok",
        "c",
        "mask",
        Some("md5(CAST(c AS VARCHAR))")
      )
    )
    store.insertColumnPolicy(
      RoleColumnPolicy("cp-deny", role.id, "*", "main", "ok", "d", "deny", None)
    )
    val lines = StoredMaskReport.linesOf(store)
    lines should have size stored.size.toLong
    stored.zip(Refused).foreach { (p, refused) =>
      val mine = lines.filter(_.contains(s"column policy ${p.id} on"))
      withClue(p.transformSql)(mine should have size 1)
      mine.head should startWith("WARN: tenant 'acme' role 'analyst': ")
      mine.head should include(s"*.main.${p.tableName}.c")
      mine.head should include(refused._2)
      mine.head should include("which is not an allowed mask form")
      mine.head should not include p.transformSql.get
    }
    lines.exists(_.contains("cp-safe")) shouldBe false
  }
