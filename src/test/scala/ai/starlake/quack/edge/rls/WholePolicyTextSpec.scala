package ai.starlake.quack.edge.rls

import ai.starlake.quack.edge.cls.TransformSqlValidator
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A mask or a row predicate is stored only when the parser read all of it. JSqlParser answers the
  * expression before a token it cannot read (`c => 1`, `c := 1` read as `c`) and takes `//`,
  * DuckDB's integer division, for a comment: a mask so stored was the raw column, and a predicate
  * judged on a part of itself could filter less than the checks saw, or, holding a closing
  * parenthesis, join the next policy's predicate once the rewriter puts them together. A predicate
  * stored before this rule is read whole at rewrite time too, or the statement is refused.
  */
class WholePolicyTextSpec extends AnyFlatSpec with Matchers:

  /** Texts JSqlParser reads only in part, as a mask over `c`. */
  private val TruncatedMasks = List(
    "c // 1000",
    "c // 1000 * 1000",
    "c => 1",
    "c := 1",
    "c # 1",
    "c @ 1",
    "c ! 1",
    "c x",
    "'a' || c AS x",
    "c || 'x' 'y'",
    "c::VARCHAR COLLATE nocase",
    "c || 'a' ORDER BY 1",
    "md5(CAST(c AS VARCHAR)) // x",
    "c || ''  // 2"
  )

  "a mask" should "be refused when the parser does not read all of it" in
    TruncatedMasks.foreach { mask =>
      withClue(mask) {
        TransformSqlValidator.validate(mask, "c") match
          case TransformSqlValidator.Invalid(reason) => reason should not include mask
          case other                                 => fail(s"stored as $other")
      }
    }

  /** Texts JSqlParser reads only in part, as a row predicate, the first ones storing a predicate
    * that keeps more rows than written.
    */
  private val TruncatedPredicates = List(
    "n // 1 = 3 AND name = 'zzz'",
    "id // 1 = 99 OR name = 'Bob'",
    "region = 'eu' => 1",
    "region = 'eu' := 1",
    "n # 1 = 0",
    "region = 'eu') OR (true",
    "region = 'eu' x",
    "owner = ${user} @ 1"
  )

  "a row predicate" should "be refused when the parser does not read all of it" in
    TruncatedPredicates.foreach { predicate =>
      withClue(predicate) {
        RowPredicateValidator.validate(predicate) shouldBe a[RowPredicateValidator.Invalid]
      }
    }

  it should "still store a whole predicate as written" in {
    RowPredicateValidator.validate("region = 'eu' AND n % 2 = 0") shouldBe
      RowPredicateValidator.Valid("region = 'eu' AND n % 2 = 0")
    RowPredicateValidator.validate("note = 'a // b'") shouldBe
      RowPredicateValidator.Valid("note = 'a // b'")
  }
