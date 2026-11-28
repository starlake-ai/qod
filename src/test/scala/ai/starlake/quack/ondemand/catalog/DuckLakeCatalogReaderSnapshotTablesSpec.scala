package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Issue #313: `listSnapshots`' `affectedTables` and its `table` filter on DuckDB 2.0, where small
  * writes commit as `inlined_insert` / `inlined_delete` and maintenance as `inline_flush`,
  * `merge_adjacent` and `rewrite_delete`, all naming the table by id. The `table` filter follows
  * the table's id, so commits from before a rename stay in its list, and a table that is no longer
  * live is still matched by name.
  */
class DuckLakeCatalogReaderSnapshotTablesSpec
    extends AnyFlatSpec
    with Matchers
    with PostgresFixture:

  private val extra =
    """CREATE TABLE lake.tpch1.orders (id INTEGER, v VARCHAR);
      |INSERT INTO lake.tpch1.orders VALUES (1, 'a');
      |INSERT INTO lake.tpch1.orders VALUES (2, 'b');
      |DELETE FROM lake.tpch1.orders WHERE id = 1;
      |CALL ducklake_flush_inlined_data('lake');
      |INSERT INTO lake.tpch1.orders SELECT range + 10, 'bulk' FROM range(100000);
      |DELETE FROM lake.tpch1.orders WHERE id < 50010;
      |CALL ducklake_rewrite_data_files('lake', 'orders', schema => 'tpch1', delete_threshold => 0.0);
      |INSERT INTO lake.tpch1.orders VALUES (3, 'c');
      |CALL ducklake_flush_inlined_data('lake');
      |INSERT INTO lake.tpch1.orders VALUES (4, 'd');
      |CALL ducklake_flush_inlined_data('lake');
      |CALL ducklake_merge_adjacent_files('lake');
      |ALTER TABLE lake.tpch1.orders RENAME TO orders2;
      |INSERT INTO lake.tpch1.orders2 VALUES (5, 'e');
      |INSERT INTO lake.tpch1.region VALUES (99, 'R99', 'x');
      |CREATE TABLE lake.tpch1.gone (id INTEGER);
      |INSERT INTO lake.tpch1.gone VALUES (1);
      |DROP TABLE lake.tpch1.gone;
      |CREATE TABLE lake.tpch1.repl (id INTEGER);
      |INSERT INTO lake.tpch1.repl VALUES (1);
      |CREATE OR REPLACE TABLE lake.tpch1.repl (id INTEGER, w VARCHAR);
      |INSERT INTO lake.tpch1.repl VALUES (2, 'x');
      |""".stripMargin

  /** The five verbs #313 names. */
  private val Verbs =
    Set("inlined_insert", "inlined_delete", "inline_flush", "merge_adjacent", "rewrite_delete")

  private def entries(changes: String): List[(String, String)] =
    changes
      .split(',')
      .toList
      .map(_.trim)
      .filter(_.contains(':'))
      .map(e => (e.takeWhile(_ != ':'), e.dropWhile(_ != ':').drop(1)))

  private def ordersId(reader: DuckLakeCatalogReader): Long =
    reader.currentTableInfo("tpch1", "orders2").getOrElse(fail("orders2 must be live"))._1

  /** Oracle independent of the reader's resolution: a snapshot touches `orders` when its raw
    * `changes_made` names the table's id, or its name before or after the rename.
    */
  private def touchesOrders(changes: String, id: Long): Boolean =
    entries(changes).exists { case (verb, payload) =>
      verb != "created_schema" && (payload == id.toString ||
        payload == "\"tpch1\".\"orders\"" || payload == "\"tpch1\".\"orders2\"")
    }

  "the fixture" should "commit every verb #313 names, and the rename" in
    withCatalog("snaptables", extraSql = extra) { (reader, _) =>
      val all      = reader.listSnapshots(limit = 1000)
      val observed = all.flatMap(s => entries(s.changes).map(_._1)).toSet
      withClue(s"observed verbs: $observed. ") {
        (Verbs -- observed) shouldBe empty
      }
      // DuckDB 2.0 commits a rename as `created_table` under the new name, a name payload the
      // reader has to resolve to the table's id.
      all.map(_.changes) should contain("created_table:\"tpch1\".\"orders2\"")
    }

  "listSnapshots" should "name the table of every inlined write and maintenance commit" in
    withCatalog("snaptables", extraSql = extra) { (reader, _) =>
      val id      = ordersId(reader)
      val carrier = reader
        .listSnapshots(limit = 1000)
        .filter(s => entries(s.changes).exists((v, p) => Verbs(v) && p == id.toString))
      carrier.map(s => entries(s.changes).map(_._1).filter(Verbs)).flatten.toSet shouldBe Verbs
      carrier.foreach { s =>
        withClue(s"snapshot ${s.snapshotId} (${s.changes}): ") {
          s.affectedTables.map(_.name).toSet should (contain("orders") or contain("orders2"))
        }
      }
    }

  it should "list every commit of a table under ?table=, before and after its rename" in
    withCatalog("snaptables", extraSql = extra) { (reader, _) =>
      val id       = ordersId(reader)
      val all      = reader.listSnapshots(limit = 1000)
      val expected = all.filter(s => touchesOrders(s.changes, id)).map(_.snapshotId)
      val got      =
        reader.listSnapshots(limit = 1000, table = Some(("tpch1", "orders2"))).map(_.snapshotId)
      got shouldBe expected
      // The creation, under the old name, is in the list.
      val created = all
        .filter(s => entries(s.changes).exists((v, _) => v == "created_table"))
        .filter(s => touchesOrders(s.changes, id))
      created should not be empty
      got should contain allElementsOf created.map(_.snapshotId)
      // The region-only insert is not.
      val regionOnly =
        all.filter(s => s.affectedTables.nonEmpty && s.affectedTables.forall(_.name == "region"))
      regionOnly should not be empty
      got should contain noElementsOf regionOnly.map(_.snapshotId)
    }

  it should "match a table that is no longer live by name" in
    withCatalog("snaptables", extraSql = extra) { (reader, _) =>
      val got = reader.listSnapshots(limit = 1000, table = Some(("tpch1", "gone")))
      got.map(s => entries(s.changes).map(_._1)).flatten.toSet should contain allOf (
        "created_table",
        "dropped_table"
      )
      got.foreach(_.affectedTables.map(_.name) should contain("gone"))
    }

  it should "follow a replaced table from the replace on, not the table it replaced" in
    withCatalog("snaptables", extraSql = extra) { (reader, _) =>
      val (id, begin) = reader.currentTableInfo("tpch1", "repl").getOrElse(fail("repl is live"))
      val got         = reader.listSnapshots(limit = 1000, table = Some(("tpch1", "repl")))
      got.map(_.snapshotId).min shouldBe begin
      got.foreach(s => s.snapshotId should be >= begin)
      got.exists(s =>
        entries(s.changes).exists((v, p) => v == "inlined_insert" && p == id.toString)
      ) shouldBe true
    }

  it should "return the newest `limit` commits of the table" in
    withCatalog("snaptables", extraSql = extra) { (reader, _) =>
      val full  = reader.listSnapshots(limit = 1000, table = Some(("tpch1", "orders2")))
      val first = reader.listSnapshots(limit = 2, table = Some(("tpch1", "orders2")))
      first.map(_.snapshotId) shouldBe full.take(2).map(_.snapshotId)
      val older = reader.listSnapshots(
        limit = 1000,
        before = Some(first.last.snapshotId),
        table = Some(("tpch1", "orders2"))
      )
      older.map(_.snapshotId) shouldBe full.drop(2).map(_.snapshotId)
    }
