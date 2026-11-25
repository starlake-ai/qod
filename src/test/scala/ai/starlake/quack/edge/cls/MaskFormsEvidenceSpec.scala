package ai.starlake.quack.edge.cls

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}

/** The evidence behind the mask allowlist ([[MaskForms]]), on DuckDB 2.0.1 in process: every
  * allowed form, over a column of every type DuckDB has (the column's type is not known when a mask
  * is written), holding the value its type answers most errors on (a type's bounds, text no
  * conversion reads, a VARIANT holding an object or a huge BIGNUM), answers either a result, a
  * binder error (the same for every row, before any row is read) or an error quoting none of the
  * value. A form the allowlist refuses is shown leaking on at least one type, so the check sees a
  * leak where there is one.
  */
class MaskFormsEvidenceSpec extends AnyFlatSpec with Matchers:

  /** One row of each type; the comments name what each value is there to provoke. */
  private val Columns: List[(String, String)] = List(
    "c_s"    -> "'SECRETVAL'::VARCHAR",
    "c_i"    -> "2147483647::INTEGER",
    "c_imin" -> "(-2147483648)::INTEGER",
    "c_ti"   -> "127::TINYINT",
    "c_si"   -> "32767::SMALLINT",
    "c_uti"  -> "255::UTINYINT",
    "c_us"   -> "65535::USMALLINT",
    "c_ui"   -> "4294967295::UINTEGER",
    "c_b"    -> "9223372036854775807::BIGINT",
    "c_bmin" -> "(-9223372036854775808)::BIGINT",
    "c_ub"   -> "18446744073709551615::UBIGINT",
    "c_h"    -> "170141183460469231731687303715884105727::HUGEINT",
    "c_hmin" -> "(-170141183460469231731687303715884105727 - 1)::HUGEINT",
    "c_uh"   -> "340282366920938463463374607431768211455::UHUGEINT",
    "c_dec"  -> "99999999999999999999999999999999999999::DECIMAL(38,0)",
    "c_dec2" -> "(-99.99)::DECIMAL(4,2)",
    "c_f"    -> "3.4e38::FLOAT",
    "c_d"    -> "1.7e308::DOUBLE",
    "c_dnan" -> "'nan'::DOUBLE",
    "c_bl"   -> "'\\xFF\\xFE'::BLOB",
    "c_bo"   -> "true",
    "c_dt"   -> "'5881580-07-10'::DATE",
    "c_ts"   -> "'294247-01-10 04:00:54.775806'::TIMESTAMP",
    "c_tss"  -> "'2020-01-01 00:00:00'::TIMESTAMP_S",
    "c_tsms" -> "'2020-01-01 00:00:00'::TIMESTAMP_MS",
    "c_tsns" -> "'2020-01-01 00:00:00'::TIMESTAMP_NS",
    "c_tstz" -> "'2020-01-01 00:00:00+00'::TIMESTAMPTZ",
    "c_tm"   -> "'23:59:59.999999'::TIME",
    "c_ttz"  -> "'23:00:00+05'::TIMETZ",
    "c_iv"   -> ("to_months(2147483647) + to_days(2147483647) + " +
      "to_microseconds(9223372036854775807)"),
    "c_e"      -> "'SECRETVAL'::mood",
    "c_l"      -> "[2147483647, -2147483648]::INTEGER[]",
    "c_ls"     -> "['SECRETVAL']::VARCHAR[]",
    "c_ll"     -> "[[1, 2], [3]]::INTEGER[][]",
    "c_arr"    -> "[1, 2]::INTEGER[2]",
    "c_st"     -> "{'k': 'SECRETVAL', 'n': 2147483647}",
    "c_stbn"   -> "{'b': repeat('7', 400)::BIGNUM}",
    "c_m"      -> "MAP {'SECRETVAL': 2147483647}",
    "c_bit"    -> "'101'::BIT",
    "c_j"      -> "'\"SECRETVAL\"'::JSON",
    "c_lj"     -> "['\"SECRETVAL\"'::JSON]",
    "c_u"      -> "'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::UUID",
    "c_un"     -> "union_value(s := 'SECRETVAL')::UNION(s VARCHAR, n INTEGER)",
    "c_bn"     -> "repeat('7', 400)::BIGNUM",
    "c_var"    -> "'SECRETVAL'::VARIANT",
    "c_vars"   -> "{'k': 'SECRETVAL'}::VARIANT",
    "c_varbn"  -> "(repeat('7', 400)::BIGNUM)::VARIANT",
    "c_varint" -> "2147483647::VARIANT",
    "c_varbl"  -> "('\\xFF\\xFE'::BLOB)::VARIANT",
    "c_varl"   -> "(['SECRETVAL', NULL])::VARIANT",
    "c_vardt"  -> "('5881580-07-10'::DATE)::VARIANT",
    "c_varj"   -> "('\"SECRETVAL\"'::JSON)::VARIANT",
    "c_varu"   -> "('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::UUID)::VARIANT",
    "c_geo"    -> "'POINT(1 2)'::GEOMETRY"
  )

  /** Fragments of the values above that an error quoting one of them would hold. */
  private val Markers = List(
    "SECRETVAL",
    "secretval",
    "2147483647",
    "2147483648",
    "4294967295",
    "65535",
    "32767",
    "9223372036854775807",
    "9223372036854775808",
    "18446744073709551615",
    "1701411834604692317",
    "3402823669209384634",
    "9999999999999999999",
    "99.99",
    "7777777777",
    "5881580",
    "294247",
    "a0eebc99",
    "3.4e+38",
    "1.7e+308",
    "xFF",
    "POINT",
    "2020-01-01",
    "23:59:59",
    "23:00:00"
  )

  private def database(): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    val row  = Columns.map((name, value) => s"$value AS $name").mkString(", ")
    List(
      "CREATE TYPE mood AS ENUM ('SECRETVAL', 'other')",
      s"CREATE TABLE t AS SELECT $row"
    ).foreach(sql => Using.resource(conn.createStatement())(_.execute(sql)))
    conn

  /** The mask written over `col`. */
  private def over(mask: String, col: String): String =
    mask.replace("\"C\"", "\"" + col + "\"").replaceAll("\\bc\\b", col)

  /** None when `mask` over `col` answers, a binder error or an error quoting no value; else the
    * error.
    */
  private def leak(conn: Connection, mask: String, col: String): Option[String] =
    val sql = s"SELECT md5(v::VARCHAR) FROM (SELECT ${over(mask, col)} AS v FROM t)"
    Try(
      Using.resource(conn.createStatement())(st => Using.resource(st.executeQuery(sql))(_.next()))
    ).failed.toOption
      .map(e => Option(e.getMessage).getOrElse(""))
      .filterNot(_.contains("Binder Error"))
      .filter(m => Markers.exists(m.contains))

  "every allowed mask form" should "answer no error quoting the value, over every type" in
    Using.resource(database()) { conn =>
      val leaks = for
        mask     <- MaskFormCases.Allowed
        (col, _) <- Columns
        error    <- leak(conn, mask, col)
      yield s"$mask over $col: ${error.take(160)}"
      leaks shouldBe Nil
      // An internal error invalidates the database, and every later statement then fails with a
      // text holding none of the markers: the database must still answer.
      Using.resource(conn.createStatement())(st =>
        Using.resource(st.executeQuery("SELECT count(*) FROM t"))(rs => rs.next() shouldBe true)
      )
    }

  "a refused form" should "leak on at least one type (the check sees a leak)" in
    Using.resource(database()) { conn =>
      List(
        "md5(c)",
        "length(c)",
        "c / 1000",
        "round(c, -3)",
        "c % 10",
        "c + 1",
        "nullif(c, 0)",
        "coalesce(c, 1.5)",
        "coalesce(c, -1)",
        "coalesce(c || '*', c)",
        "CAST(c AS INTEGER)",
        "c::VARCHAR[]",
        // TRY(c IN (1, 2)) leaked on 2.0.0 and answers over every type on 2.0.1; TRY and a
        // comparison stay refused, as the forms around it still leak.
        "TRY(timezone(c, TIMESTAMP '2020-01-01'))",
        "CASE WHEN c THEN 'a' END",
        "c[CAST(c AS INTEGER)]",
        "upper(c)[c::INTEGER]"
      ).foreach { mask =>
        withClue(mask) {
          Columns.flatMap((col, _) => leak(conn, mask, col)) should not be empty
        }
      }
    }
