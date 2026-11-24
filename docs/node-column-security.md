# Native column security

## Status and rollout

This adds opt-in read-side CLS on DuckDB 2.0.1's bound plan. Set
`QOD_CLS_ENFORCEMENT=node` (`quack-on-demand.cls.enforcement=node`) on every manager
replica. The default remains `gateway`. Invalid modes refuse boot.
`QOD_CLS_ENABLED=false` disables policy transport and both enforcement paths.

Node protocol **8** and signed handle version **6** are mandatory in both modes.
Rebuild and vendor every platform's node binary, deploy matching node images and
fleet launchers, and restart the nodes. Earlier nodes and handles refuse. This PR
must remain draft until those binaries are supplied. No policy-table or DuckLake
metadata migration is needed.

The contract is: apply the policies correctly or refuse. SQL still binds against
its original source schema. A query that fails that binding is not rescued by
rewriting it around a type-changing mask.

## Signed policies and independent binding

The manager resolves the effective, token-attenuated column policies, sorts them
by id, and validates each mask with the existing production transform validator.
That includes stored/imported policies: a mask outside the allowed forms refuses
native policy minting with a policy reference, never its SQL. Gateway mode keeps
its existing behavior. Superuser and internal handles carry no column policies.

Every handle requires a `columnPolicies` array, bounded to 256 entries and 4096
UTF-8 bytes of SQL per mask. Entries carry
`{id,catalog,schema,table,column,action,sql}`; a deny has empty SQL. The MAC covers
the entire payload. The native reader rejects malformed actions and payloads.

The node matches actual source catalog, schema, table and column names using
DuckDB's identifier comparison. A deny wins. A mask naming a table outranks a
table-wildcard mask, and different remaining masks conflict. Scoping is retained
before precedence, so a schema wildcard cannot affect another schema's table.

Each active mask binds independently, with no parent query and only its own
column available. Calls must resolve to classified, allowed system functions;
caller macros, settings, variables, subqueries, parameters, windows and lambdas
are refused. Binding errors have fixed client text. The result must have exactly
the bound source type. A literal NULL is bound as a typed NULL; other incompatible
types refuse. No implicit cast is added to make a type-changing mask fit.

Masks evaluate under `TRY`: an evaluation failure produces NULL rather than an
error quoting the original value. This is a documented native-mode behavior.

## Plan placement and RLS composition

Before optimization, every covered source scan receives a masking projection.
The raw scan gets a fresh private binding index; the projection retains the
caller's original binding index and column positions. Callers cannot retain a
reference to the raw index through ordinary view, alias or derived-table reads.
The projection is enclosed in a node-created secure-view optimizer boundary.
The describer recognizes this private boundary and does not grant definer rights.

CLS placement runs before RLS insertion, so the resulting execution layout is:

Raw source scan → RLS filter (when applicable) → masking projection → caller
expressions, with optimizer boundaries around the policy stages.

RLS therefore reads raw values and caller expressions see masked values. Columns
added to the scan solely for an RLS predicate are absent from the caller-facing
projection. A deny does not block an administrator's predicate from privately
using that column. A derived table or view consumes the masked source and does
not apply its base mask again.

Before optimization the node checks the boundary, projection, mask expressions,
source pointer, binding indexes, original scan-column positions and signed policy
vector again. A later post-bind extension removing a boundary or changing a mask
is refused. As with native RLS, extensions mutating plans after this verification
remain trusted native code.

Prepared authorization records include the full signed column-policy vector.
Changes in identity, context, policy version or policy vector force rebinding,
including when the version is unchanged but the signed mask changed.

## Deliberate limits

- Masks preserve source types. Type-changing masks such as `hash(varchar_column)`
  refuse when that column is read. Use a same-type transform such as an explicit
  validated VARCHAR representation when appropriate.
- Policies naming a generated column's own output refuse reads of its table.
  Policies on physical inputs do apply before generated expressions consume them.
- A policy covering a view's or table macro's own coordinates refuses that read,
  including a matching wildcard policy. Scope the policy to the base tables.
  Policies naming a base table apply through ordinary and secure views, preserving
  the independent secure-view grant rules.
- Deny detection is conservative before optimization. A view or CTE that projects
  a denied column can refuse even if the caller selects another column. No unused
  denied value is exposed to make that query run.
- Reads with pre-existing scan projections, extracted nested scan columns or
  other unsupported scan shapes refuse. Ordinary nested-value consumers can use
  masks only where the full source type and bindings are preserved.
- Writes and DDL targeting covered tables, INSERT included, refuse natively.
  Existing gateway protected-write checks stay mounted. This does not introduce
  write-check policies or a general secure-write feature.
- Existing historical-name restrictions remain. DuckLake change feeds refuse
  for native column-policy holders because they have no ordinary source scan to
  mask. File readers without table identity retain file-location authorization;
  they do not acquire name-scoped table policies.
- Gateway metadata filtering, routing and conservative syntax/write guards remain.
  Node mode skips the gateway column rewrite and its SQL normalization; it does
  not promise every syntax form is newly admitted by the remaining guards.
- Gateway rewrite metrics report passthrough in node mode. That means no gateway
  rewrite was performed, not that native policies were bypassed.

EXPLAIN sees the opaque boundary rather than its mask expressions. Query text is
not populated with mask SQL; existing statement-text and metadata authorization
restrictions remain. Timing, resource use, privileged/internal connections and
trusted native extensions are outside this read-side guarantee.

## Verification

`column_policy_engine_test` exercises real node hooks and signed session handles,
including ordinary/computed/secure views, generated expressions, aliases, CTEs,
correlation, both join sides, NATURAL/USING joins, NULL extension, whole-row/list
consumers, predicates, aggregates, dynamic queries, positional reads, precedence,
cross-schema scope, typed NULLs, error handling, prepared-policy changes and later
extension tampering. DuckLake cases run when its pinned extension is installed.

Scala tests cover validation of legacy masks, full policy scopes, signing and
verification, bounds, privileged callers, transport opt-in and bypassing gateway
rewriting. The cross-language handle fixture pins the version transition.

Complete socket-dependent transport/process checks in CI before merging. At each
DuckDB upgrade, revisit scan-column mappings, secure-view optimization, TRY,
independent binding, prepared-query lifecycle and descriptor behavior. These are
internal C++ interfaces, not a stable extension ABI.
