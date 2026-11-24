// src/test/scala/ai/starlake/quack/ondemand/catalog/DuckLakeCatalogReaderMemberSnapshotsSpec.scala
package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.api.CatalogTableRef
import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Maintainer's review of #321, the reader side: how `changes_made` is read (quotes respected, `""`
  * unescaped, the unparseable left unattributed), and the member reads of the snapshots.
  */
class DuckLakeCatalogReaderMemberSnapshotsSpec
    extends AnyFlatSpec
    with Matchers
    with PostgresFixture:

  /** Test texts are written with `'` for the `"` of the catalog's own text. */
  private def q(text: String): String = text.replace('\'', '"')

  "splitChanges" should "cut on the commas outside quotes only" in {
    DuckLakeCatalogReader.splitChanges(q("created_table:'s'.'a,b',inserted_into_table:3")) shouldBe
      Some(List(q("created_table:'s'.'a,b'"), "inserted_into_table:3"))
  }

  it should "keep a doubled quote inside the name it belongs to" in {
    DuckLakeCatalogReader.splitChanges(q("created_table:'s'.'o'',''k',dropped_table:4")) shouldBe
      Some(List(q("created_table:'s'.'o'',''k'"), "dropped_table:4"))
  }

  it should "answer nothing for a quote left open" in {
    DuckLakeCatalogReader.splitChanges(q("created_table:'s'.'t,inserted_into_table:3")) shouldBe
      None
  }

  it should "answer no entry for an empty text" in {
    DuckLakeCatalogReader.splitChanges("") shouldBe Some(Nil)
  }

  "parseQualified" should "read two quoted names" in {
    DuckLakeCatalogReader.parseQualified(q("'sales'.'orders'")) shouldBe
      Some(("sales", "orders"))
  }

  it should "unescape a doubled quote" in {
    DuckLakeCatalogReader.parseQualified(q("'sales'.'o''k'")) shouldBe Some(("sales", "o\"k"))
    DuckLakeCatalogReader.parseQualified(q("'sales'.'o''''k'")) shouldBe
      Some(("sales", "o\"\"k"))
  }

  it should "keep a dot or a comma inside a name" in {
    DuckLakeCatalogReader.parseQualified(q("'sa.les'.'a,b'")) shouldBe Some(("sa.les", "a,b"))
    DuckLakeCatalogReader.parseQualified(q("'s'.'a'.'b'")) shouldBe None
  }

  it should "answer nothing for what is not two quoted names" in
    List(
      "'sales'",
      "sales.orders",
      "'sales'.'orders'x",
      "'sales'.'orders",
      "'sales' 'orders'",
      "'sales'.'",
      ""
    ).map(q).foreach(p => withClue(p)(DuckLakeCatalogReader.parseQualified(p) shouldBe None))

  // ------------------------------------------------------------------

  private val quotedNames =
    q(
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.'o''k' (id INTEGER);
        |CREATE TABLE lake.sales.'o''''k' (id INTEGER);
        |CREATE TABLE lake.sales.'a,b' (id INTEGER);
        |INSERT INTO lake.sales.'a,b' VALUES (1);
        |""".stripMargin
    )

  "listSnapshotsForMember" should "name a table whose name holds a quote or a comma as it is written, and attribute it (real DuckLake)" in
    withCatalog("memberquotes", quotedNames) { (reader, _) =>
      val member  = reader.listSnapshotsForMember(100)
      val created = member.filter(m =>
        m.entry.changes.startsWith("created_table") && m.entry.changes.contains("\"sales\"")
      )
      withClue(member.map(m => m.entry.changes -> m.entry.affectedTables)) {
        created.flatMap(_.entry.affectedTables).toSet shouldBe
          Set(
            CatalogTableRef("sales", "o\"k"),
            CatalogTableRef("sales", "o\"\"k"),
            CatalogTableRef("sales", "a,b")
          )
        created.foreach(_.accounted shouldBe true)
        // The write to the table named `a,b` is attributed too: no comma split in its entries.
        member
          .filter(_.entry.changes.startsWith("inlined_insert"))
          .foreach(_.accounted shouldBe true)
      }
    }

  it should "leave a snapshot of a dropped table unattributed (real DuckLake)" in
    withCatalog(
      "memberdropped",
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.keep (id INTEGER);
        |CREATE TABLE lake.sales.gone (id INTEGER);
        |INSERT INTO lake.sales.gone VALUES (1);
        |""".stripMargin
    ) { (reader, _) =>
      runSqlOnCatalog("DROP TABLE lake.sales.gone;")
      val member = reader.listSnapshotsForMember(100)
      val names  = member.flatMap(_.entry.affectedTables).filter(_.schema == "sales").distinct
      names shouldBe List(CatalogTableRef("sales", "keep"))
      member
        .filter(m => m.entry.changes.contains("gone") || m.entry.changes.startsWith("dropped"))
        .foreach(_.accounted shouldBe false)
      // The admin's listing keeps the name each snapshot had.
      reader.listSnapshots(100).flatMap(_.affectedTables).distinct should contain(
        CatalogTableRef("sales", "gone")
      )
    }

  "listSnapshotsByIdsForMember" should "take more ids than the JDBC limit on bind parameters (real DuckLake)" in
    withCatalog(
      "memberids",
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.orders (id INTEGER);
        |INSERT INTO lake.sales.orders VALUES (1);
        |""".stripMargin
    ) { (reader, _) =>
      val everything = reader.listSnapshotsForMember(100).map(_.entry.snapshotId).toSet
      // Past 32767 (the review's figure) and past 65535, what this pgjdbc accepts.
      val ids = (0L until 70000L).toSet
      ids.size should be > 65535
      reader.listSnapshotsByIdsForMember(ids).map(_.entry.snapshotId).toSet shouldBe everything
      // Read under a policy (each table's name history checked), the same: no bind per id.
      val underPolicy =
        reader.listSnapshotsForMember(100, underPolicy = true).map(_.entry.snapshotId).toSet
      reader
        .listSnapshotsByIdsForMember(ids, underPolicy = true)
        .map(_.entry.snapshotId)
        .toSet shouldBe underPolicy
    }
