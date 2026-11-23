package ai.starlake.quack.edge.cls

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TransformSqlValidatorSpec extends AnyFlatSpec with Matchers:
  import TransformSqlValidator._

  "validate" should "accept a constant string" in {
    validate("'***'", "c_email") shouldBe a[Valid]
  }

  it should "accept a built-in function over the protected column" in {
    validate("md5(c_email)", "c_email") shouldBe a[Valid]
    validate("concat('user_', md5(c_email))", "c_email") shouldBe a[Valid]
    validate("regexp_replace(c_phone, '\\d', 'X')", "c_phone") shouldBe a[Valid]
  }

  it should "canonicalise whitespace" in {
    val r = validate("  '***'   ", "c_email")
    r match
      case Valid(canon) => canon shouldBe "'***'"
      case other        => fail(s"$other")
  }

  it should "reject when the expression references a different column" in {
    val r = validate("concat(c_email, c_phone)", "c_email")
    r shouldBe a[Invalid]
    r.asInstanceOf[Invalid].reason should include("c_phone")
  }

  it should "reject subqueries" in {
    validate("(SELECT c_email FROM customer WHERE c_id = 1)", "c_email") shouldBe a[Invalid]
  }

  it should "reject denylisted functions" in {
    validate("attach('s3://bucket/x.parquet')", "c_email") shouldBe a[Invalid]
    validate("read_parquet('s3://x.parquet')", "c_email") shouldBe a[Invalid]
    validate("pragma_database_size('main')", "c_email") shouldBe a[Invalid]
    validate("pg_read_server_files('/etc/passwd')", "c_email") shouldBe a[Invalid]
  }

  it should "reject when the expression fails to parse" in {
    validate(") not sql (", "c_email") shouldBe a[Invalid]
  }

  it should "reject when the canonicalised form exceeds 1024 chars" in {
    val long = "concat(" + List.fill(200)("c_email").mkString(", '_', ") + ")"
    validate(long, "c_email") shouldBe a[Invalid]
  }

  // ---- comment/semicolon splice safety (jsqlparser accepts and silently truncates at these
  // rather than failing the parse, so they must be rejected explicitly before parsing) ----

  it should "reject a trailing line comment" in {
    val r = validate("md5(c_email) -- x", "c_email")
    r shouldBe a[Invalid]
    r.asInstanceOf[Invalid].reason should include("comments")
  }

  it should "reject an embedded block comment" in {
    val r = validate("md5(c_email) /* x */", "c_email")
    r shouldBe a[Invalid]
    r.asInstanceOf[Invalid].reason should include("comments")
  }

  it should "accept a '--' that appears inside a string literal" in {
    validate("concat('--', c_email)", "c_email") shouldBe a[Valid]
  }

  it should "reject a bare semicolon" in {
    val r = validate("md5(c_email); DROP TABLE secrets", "c_email")
    r shouldBe a[Invalid]
    r.asInstanceOf[Invalid].reason should include("statement separator")
  }

  it should "accept a semicolon that appears inside a string literal" in {
    validate("concat('a;b', c_email)", "c_email") shouldBe a[Valid]
  }

  // ---- the column check reads a mask as the rewriter splices it (review round 2, m1) ---------

  it should "reject another column in every position the rewriter reads" in
    List(
      "CASE WHEN ssn IN (id) THEN '***' ELSE ssn END",
      "CASE WHEN id IS NULL THEN '***' ELSE ssn END",
      "CASE WHEN id BETWEEN 1 AND 2 THEN '***' END",
      "CASE WHEN NOT id THEN '***' END",
      "[id][1]",
      "@id",
      "CAST(current AS VARCHAR)",
      "@ssn"
    ).foreach { mask =>
      withClue(mask)(validate(mask, "ssn") shouldBe a[Invalid])
    }

  it should "accept a lambda whose parameter shares or does not share the column's name" in {
    validate("list_filter(l, lambda l: len(l) < 3)", "l") shouldBe a[Valid]
    validate("list_filter(l, l -> len(l) < 3)", "l") shouldBe a[Valid]
    validate("list_transform(l, lambda x: concat(x, '*'))", "l") shouldBe a[Valid]
    validate("list_transform(l, x -> concat(x, '*'))", "l") shouldBe a[Valid]
  }

  it should "accept a SQL value function and a time keyword naming the column" in {
    validate("CAST(current_date AS VARCHAR)", "ssn") shouldBe a[Valid]
    validate("left(current_date, 2) || '***'", "current_date") shouldBe a[Valid]
    validate("CASE WHEN \"SSN\" IS NULL THEN '***' END", "ssn") shouldBe a[Valid]
  }
