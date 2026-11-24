# Row security on the bound DuckDB plan

## Status and rollout

This implements opt-in read-side RLS in qod-node, using the pinned DuckDB 2.0.1 C++
planner interfaces. Enable it with `QOD_RLS_ENFORCEMENT=node` (or
`quack-on-demand.rls.enforcement=node`). The default remains `gateway`.
`QOD_RLS_ENABLED=false` disables both implementations. Invalid enforcement modes
refuse boot. Every manager replica must use the same setting.

Node protocol 9 and signed handle version 7 are mandatory, including in gateway mode.
Rebuild and vendor every platform's qod-node binary, deploy the matching node image
and fleet CLI, and restart the nodes before enabling the feature. Older handles and nodes fail closed. No policy-table or DuckLake metadata migration is required.

## Data flow and trust

Table ACLs decide whether a principal may read a table. With a read grant and no
applicable row policy, reads remain unfiltered. Applicable policies combine with OR.
This is the QoD contract; enabling RLS does not introduce default-deny table access.

The manager resolves the caller's effective, token-attenuated policies and expands
identity tokens as escaped SQL values. Tokens must be standalone values, outside SQL
literals and quoted identifiers; unknown tokens and unsupported predicate forms are
rejected at creation and manifest import. Stored invalid predicates, or identity
expansions over 64 KiB, become deliberately unbindable entries on their original
scope. They deny only matching table access, without breaking unrelated statements.
Boot diagnostics and administrator logs identify the policy without its predicate.

Production handles carry a SHA-256 reference to an immutable policy snapshot instead
of embedding every expanded predicate. The private `/api/internal/authz/row-policies`
endpoint checks the request HMAC, signed handle, and the exact handle's issuance by
this replica. It serves signed pages of at most 64 policies and 512 KiB of encoded
entries, keeping responses under the HTTP 1 MiB bound. The node verifies the page
signature, snapshot id, offset and continuation before installing any policies.
A partial or invalid fetch installs nothing and returns an authenticated retry marker.

Snapshots are content-addressed, so a different identity expansion or policy body
has a different reference even at the same policy version. Node snapshots are cached
for five minutes with a 32 MiB total serialized budget and 1,024-entry bound; a fetch
also refuses past 32 MiB. Manager snapshots have a 128 MiB serialized budget and six
minutes of idle retention. Cache eviction or snapshot capacity exhaustion is an
explicit authorization-availability failure, never unfiltered access. There is no
256-policy limit on production snapshots; the inline handle representation retained
for small test fixtures remains bounded. Empty, internal and superuser snapshots
need no fetch.

The node verifies the handle before installing policies on the connection. A statement's
`post_bind` hook visits DuckDB's resolved base-table scans, including scans in plain
views, literal `query()`, `query_table()`, CTEs and expression subqueries. Coverage
uses DuckDB's own case-insensitive identifier comparison, with the existing `*`
wildcards. All matching policies are combined with OR, as in gateway mode.

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
or any core or extension session setting override except schema/search_path refuse; RESET can recover the
session. Explicit collations in the administrator's predicate remain explicit.

Before optimization, QoD verifies that every protected scan is still immediately
inside its original filter and boundary, that the bound filter expressions are
unchanged, that scan column ids, projection ids, table index, names and types are
unchanged, and that the policies match the current handle. An extension registering
a later post-bind callback cannot remove or replace the filter unnoticed. Enforcement
starts at post-bind and does not depend on an optional optimizer pass being enabled.

Prepared statements store the policy vector with their authorization. A different
identity, policy version, session context or signed policy vector forces rebinding.
An unchanged policy version cannot cause an old predicate to be reused after a
different signed policy vector is installed.

## Deliberate limits

- These are base-table policies. An exact policy scoped to a view is refused on reads
  of that view, including nested reads; move it to the base table or use gateway mode.
  Wildcard policies apply to base scans, not to a view's projected aliases.
- The reader's base-table policies also apply inside a real secure view. The view's
  definer-rights table-grant rules remain unchanged.
- Ordinary users with an applicable policy on a target table cannot write it, INSERT
  included, even with an INSERT grant. A loader with an INSERT grant and no applicable
  row policy can insert. Superuser and internal connections bypass this RLS restriction;
  read-only catalog rules still apply. A future write
  policy must distinguish visibility (`USING`) from validation of proposed values
  (`WITH CHECK`). The gateway's protected-write checks stay mounted too.
- CLS remains a gateway rewrite. When the two features coexist, native row filters
  execute at the base scans, before the gateway-generated masks.
- Gateway checks needed by CLS, metadata filtering, routing and protected writes
  remain. Node RLS does not promise unrestricted DuckDB syntax on those paths.
- File-reading functions without a bound base-table identity remain governed by
  file-location authorization, not name-scoped table policies.
- Fresh catalog objects are not writes to existing protected rows. Wildcards do not
  forbid creating tables, views, macros or sequences. Replacement and destructive DDL
  on covered existing tables remain refused.
- EXPLAIN and EXPLAIN ANALYZE over protected scans refuse because their estimates or
  profiling can expose hidden row counts. `stats()` exposes no hidden column extrema.
- DuckLake change feeds refuse for policy holders, including through views and literal
  dynamic queries, because their output has no ordinary filterable base scan.
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
verification, wire bounds, and the gateway/native guard split. Cross-language golden
handles and the existing native authorization tests pin the version transition.

At each DuckDB upgrade, review `post_bind` ordering, generic binding, scan column-id
mapping, secure-view filter pushdown, TRY semantics, serialization and prepared-query
rebinding. These are internal C++ interfaces, not a stable extension ABI.

## Administration and diagnostics

Native binding failures emit a structured node log with policy id, table scope and
cause, never predicate SQL, substituted identity values or hidden data. The manager
exports `qod_node_authz_row_policy_refusals_total` with tenant/pool/node labels through
its existing health probe. The boot report checks unsupported stored expressions,
including wildcard scopes, in addition to missing-column diagnostics. Full native
type and function-resolution checks still run against the actual scan at bind time.

The protocol generation differs from the separately stacked CLS implementation;
that stack must be rebased and fenced consistently before a combined deployment.
