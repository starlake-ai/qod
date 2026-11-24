# Row security on the bound DuckDB plan

## Status and rollout

Row policies are enforced exclusively by qod-node using the pinned DuckDB 2.0.1 C++
planner interfaces. The gateway no longer rewrites row-policy queries, and there is
no gateway fallback or enforcement-mode switch. `QOD_RLS_ENABLED=false` disables RLS.
Every manager replica must use the same setting.

Node protocol 8 and signed handle version 6 are mandatory. Rebuild and vendor every
platform's qod-node binary, deploy the matching node image and fleet CLI, and restart
the nodes with this release. Earlier handles and nodes fail closed. No
policy-table or DuckLake metadata migration is required.

## Data flow and trust

The manager resolves the caller's effective, token-attenuated policies. It substitutes
the authenticated user's identity using SQL-literal escaping,
sorts policies by id, and puts `{id,catalog,schema,table,sql}` entries in the signed
session handle's required `rowPolicies` array. Superuser and internal handles carry
an empty array. Unknown identity tokens refuse instead of silently becoming NULL.
The handle permits at most 256 policies and 64 KiB of expanded UTF-8 SQL per predicate.

The node verifies the handle before installing policies on the connection. A statement's
`post_bind` hook visits DuckDB's resolved base-table scans, including scans in plain
views, literal `query()`, `query_table()`, CTEs and expression subqueries. Coverage
uses DuckDB's own case-insensitive identifier comparison, with the existing `*`
wildcards. All matching policies are combined with OR, using the existing policy-composition semantics.

Each predicate is parsed independently by DuckDB. Combination uses expression trees,
never concatenated policy SQL. A separate binder with no parent query binds the
predicate against the scanned table's actual names and types. Only that table's
bindings may escape the binder. Required predicate columns are appended to the scan
without changing existing column positions, including for `COUNT(*)`. Policy functions
must resolve to classified, allowed system functions; user macros, variables, catalog
reads, subqueries, window expressions and lambdas in policies refuse.

## Evaluation boundary

Each protected scan becomes a filter inside a node-created `LogicalSecureView`
subclass. This reuses DuckDB's optimizer boundary: caller predicates that can raise
errors or have effects receive DuckDB's expression barriers, and statistics from
hidden rows do not propagate as public column statistics. The node's describer
recognizes this specific C++ type as a row filter, not a catalog secure view. It
does not introduce a view grant or change the caller's underlying table grants.

Each policy evaluates as `TRY(CAST(predicate AS BOOLEAN))` before OR combination:
an evaluation error or NULL grants no access through that policy; another policy
can still grant the row. This is deliberately more restrictive than returning an error
that might quote a hidden value. Binding failures return a fixed refusal without
the predicate, identity literals or DuckDB's binder excerpt. Volatile predicates
that DuckDB cannot evaluate under TRY refuse. Reads under a non-default collation
or caller overrides of expression-meaning settings refuse; RESET can recover the
session. Explicit collations in the administrator's predicate remain explicit.

Before optimization, QoD verifies that every protected scan is still immediately
inside its original filter and boundary, that the bound filter expressions are
unchanged, and that the policies match the current handle. An extension registering
a later post-bind callback cannot remove or replace the filter unnoticed. Enforcement
starts at post-bind and does not depend on an optional optimizer pass being enabled.

Prepared statements store the policy vector with their authorization. A different
identity, policy version, session context or signed policy vector forces rebinding.
An unchanged policy version cannot cause an old predicate to be reused after a
different signed policy vector is installed.

## Deliberate limits

- These are base-table policies. An exact policy scoped to a view is refused on reads
  of that view, including nested reads; move it to the base table.
  Wildcard policies apply to base scans, not to a view's projected aliases.
- The reader's base-table policies also apply inside a real secure view. The view's
  definer-rights table-grant rules remain unchanged.
- Writes to a covered table, INSERT included, are refused by the node. A future write
  policy must distinguish visibility (`USING`) from validation of proposed values
  (`WITH CHECK`). The gateway's protected-write checks stay mounted too.
- CLS remains a gateway rewrite. When the two features coexist, native row filters
  execute at the base scans, before the gateway-generated masks.
- Gateway checks needed by CLS, metadata filtering, routing and protected writes
  remain. Node RLS does not promise unrestricted DuckDB syntax on those paths.
- File-reading functions without a bound base-table identity remain governed by
  file-location authorization, not name-scoped table policies.
- DuckLake change feeds refuse for row-policy principals, including inside views and
  literal dynamic queries, because their rows have no filterable base-table scan.
- Existing fail-closed historical-name checks remain in place. Policy coverage is
  still name-based, not an immutable table-id model.
- Timing, resource use, administrator or internal connections, and native code loaded
  by trusted extensions remain outside row confidentiality guarantees. An optimizer
  extension that mutates the plan after QoD's pre-optimization check remains trusted.

## Verification

`row_policy_engine_test` executes real DuckDB plans under signed handles. It covers
views, CTE materialization, both join sides, outer joins, correlated subqueries,
set operations, dynamic queries, count projections, rowids, sampling, PIVOT,
predicate errors, caller errors, independent predicate parsing, missing-column
capture attempts, function shadowing, policy composition, prepared-statement policy
changes, privileged connections, write refusals and a later extension removing the
filter. DuckLake cases also run when the pinned extension is installed.

Scala tests verify identity expansion, unknown-token refusal, handle signing and
verification, wire bounds, unchanged row-only query text, the RLS kill switch, and protected-write
and historical-name checks. Cross-language golden
handles and the existing native authorization tests pin the version transition.

At each DuckDB upgrade, review `post_bind` ordering, generic binding, scan column-id
mapping, secure-view filter pushdown, TRY semantics, serialization and prepared-query
rebinding. These are internal C++ interfaces, not a stable extension ABI.

## Removed gateway machinery

The runtime `RowPolicyRewriter`, its configuration mode, router stage, rewrite timers
and dashboard panels are removed. Identity expansion remains as a small helper for
signed handles. Static predicate-column analysis remains solely for boot diagnostics;
it never transforms submitted SQL. Administrative predicate validation, the RLS
kill switch, CLS rewriting, metadata filtering and protected-write checks remain.
