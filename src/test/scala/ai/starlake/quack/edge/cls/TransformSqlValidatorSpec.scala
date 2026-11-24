package ai.starlake.quack.edge.cls

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TransformSqlValidatorSpec extends AnyFlatSpec with Matchers:
  import TransformSqlValidator._

  "validate" should "accept a constant string" in {
    validate("'***'", "c_email") shouldBe a[Valid]
  }

  it should "accept a built-in function over the protected column read as text" in {
    validate("md5(CAST(c_email AS VARCHAR))", "c_email") shouldBe a[Valid]
    validate("concat('user_', md5(CAST(c_email AS VARCHAR)))", "c_email") shouldBe a[Valid]
    validate("regexp_replace(CAST(c_phone AS VARCHAR), '\\d', 'X')", "c_phone") shouldBe a[Valid]
  }

  it should "refuse md5, sha1, sha256 and length over the bare column, whose type is not known" in {
    validate("md5(c_email)", "c_email") shouldBe a[Invalid]
    validate("length(c_email)", "c_email") shouldBe a[Invalid]
  }

  it should "admit the text functions DuckDB answers over a value of every type" in {
    validate("regexp_replace(c_phone, '\\d', 'X')", "c_phone") shouldBe a[Valid]
    validate("left(c_phone, 3)", "c_phone") shouldBe a[Valid]
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

  it should "refuse a lambda, which no allowed mask form holds" in {
    validate("list_filter(l, lambda l: len(l) < 3)", "l") shouldBe a[Invalid]
    validate("list_filter(l, l -> len(l) < 3)", "l") shouldBe a[Invalid]
    validate("list_transform(l, lambda x: concat(x, '*'))", "l") shouldBe a[Invalid]
    validate("list_transform(l, x -> concat(x, '*'))", "l") shouldBe a[Invalid]
  }

  it should "refuse a time keyword, and accept a quoted column of that name" in {
    validate("CAST(current_date AS VARCHAR)", "ssn") shouldBe a[Invalid]
    validate("left(current_date, 2) || '***'", "current_date") shouldBe a[Invalid]
    validate("left(CAST(\"current_date\" AS VARCHAR), 2) || '***'", "current_date") shouldBe
      a[Valid]
    validate("CASE WHEN \"SSN\" IS NULL THEN '***' END", "ssn") shouldBe a[Valid]
  }

  it should "reject a star, a COLUMNS(..) expression or a parameter (review round 3, m-r1)" in
    List(
      "concat(ssn, COLUMNS('id'))",
      "COLUMNS('ss.*')",
      "to_json(*)",
      "struct_pack(*)",
      "concat(ssn, $1)",
      "concat(ssn, ?)"
    ).foreach { mask =>
      withClue(mask)(validate(mask, "ssn") shouldBe a[Invalid])
    }

  it should "read a mask in method form as DuckDB does (review round 5, R3-I1)" in {
    List(
      "secret.upper()",
      "concat(ssn, secret.lower())",
      "secret.max()",
      "u.secret.upper()",
      "ssn.max() FILTER (WHERE true)"
    ).foreach(mask => withClue(mask)(validate(mask, "ssn") shouldBe a[Invalid]))
    // A method call is no allowed form (the call it stands for takes text, and a qualified name
    // could be a macro): refused, as the plain forms over CAST(ssn AS VARCHAR) are not.
    List("ssn.upper()", "ssn.left(2) || '***'", "main.upper(ssn)", "ssn.upper().lower()")
      .foreach(mask => withClue(mask)(validate(mask, "ssn") shouldBe a[Invalid]))
    validate("upper(CAST(ssn AS VARCHAR))", "ssn") shouldBe a[Valid]
  }
