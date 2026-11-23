package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.sql.FunctionCalls

/** Functions and views that return the text of the statement the node runs, refused by the
  * protected-write guard to a principal with column or row policies, on every statement kind. The
  * node runs the rewritten statement, so its text holds the policies applied to it: the predicate
  * of every row policy and the alias of every filtered view. A caller who reads the alias can name
  * it (a PIVOT pre-statement turns a value into a column of that name), and the predicates are an
  * administrator's.
  *
  * Listed from the DuckDB 2.0 CLI (`duckdb_functions()`, every function whose name or description
  * speaks of a query, a statement or SQL) and `function-classes.tsv`: `current_query()` (also the
  * `pg_catalog.current_query` macro) returns the running statement's text, a PIVOT pre-statement's
  * own; `duckdb_prepared_statements()` and its view `pg_catalog.pg_prepared_statements` list the
  * connection's prepared statements with their text. `current_query_id()` returns a number;
  * `json_serialize_sql`, `sql_tokenize` and `sql_auto_complete` read only their argument;
  * `duckdb_format_sql`, `duckdb_logs` and `json_execute_serialized_sql` are superuser-only.
  *
  * Lexical, as [[CatalogReachingCalls]]: calls through [[FunctionCalls]] (any quoting or
  * qualification, string literal bodies scanned too), and a name anywhere in a scanned text for the
  * view and the table function, which DuckDB also reads without parentheses in a FROM clause. A
  * column so named is refused too: the safe direction.
  */
object StatementTextScreen:

  val Functions: Set[String] = Set("current_query", "duckdb_prepared_statements")

  val Names: Set[String] = Set("pg_prepared_statements", "duckdb_prepared_statements")

  private val NamePattern =
    ("(?i)(?<![a-z0-9_$])(" + Names.mkString("|") + ")(?![a-z0-9_$])").r

  /** The first statement-text function or view `sql` reaches, lowercased, or None. */
  def find(sql: String): Option[String] =
    val scan = FunctionCalls.scan(sql)
    scan.calls.iterator
      .map(_.name)
      .find(Functions)
      .orElse(
        scan.texts.iterator
          .flatMap(t => NamePattern.findFirstMatchIn(t).map(_.group(1).toLowerCase))
          .nextOption()
      )

  def denyReason(name: String): String =
    s"$name returns the text of the statement the node runs, which holds the row and column " +
      "policies applied to it; not available to principals with column or row policies " +
      "(deny, fail-closed)"
