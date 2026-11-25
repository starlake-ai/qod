# Native column security

## Status and rollout

CLS runs exclusively on DuckDB 2.0.1's bound plan. It is enabled by default;
`QOD_CLS_ENABLED=false` disables signed column-policy transport and the CLS write
guard. Gateway rewriting, `QOD_CLS_ENFORCEMENT`, and `QOD_CLS_UNRESOLVED_TABLE`
are retired. Metadata filtering and historical-name checks remain.

Node protocol **10** and signed handle version **8** are mandatory, the same generation as
[native row security](node-row-security.md).
Rebuild and vendor every platform's node binary, deploy matching node images and
fleet launchers, and restart the nodes. Earlier nodes and handles refuse.
No policy-table or DuckLake metadata migration is needed.

The contract is: apply the policies correctly or refuse. SQL still binds against
its original source schema. A query that fails that binding is not rescued by
rewriting it around a type-changing mask.

## Signed policies and independent binding

The manager validates definitions consistently on REST/SQL create/update and before
any manifest import writes rows. Imports store the same canonical expressions.
An unusable persisted mask becomes a deny on its original source column; it is
never omitted and never prevents an unrelated query from minting a handle.
Superuser and internal handles carry no column policies.

Immutable policy snapshots are cached by the complete authorization-context digest,
policy version and enable flags. Repeated handles reuse prepared policy arrays;
mask-validation results are bounded and keyed by SQL and protected column. Changes
in identities, token restrictions, policy contents, pools or versions cannot reuse
another snapshot. Both policy kinds share the same attenuation step.

Production handles carry the digest of one immutable snapshot containing both
row and column policies. The private `/api/internal/authz/policies` endpoint serves
signed pages of at most 64 policies and 512 KiB, only for a handle minted by that
replica. The node verifies every page and installs both policy kinds atomically;
an incomplete, unavailable or invalid snapshot cannot run a user statement.
Snapshots are cached by digest on the node, with a 32 MiB byte budget and bounded
cache retention. Handles stay small as policy counts grow. A mask retains its
4096 UTF-8 byte limit. No policy list is truncated or narrowed using a manager's
guessed catalog inventory: native binding decides applicability. Column entries
carry `{kind,id,catalog,schema,table,column,action,sql}`; a deny has empty SQL.

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
error quoting the original value. This is part of the native masking contract.

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
- A policy explicitly naming a view's or user table macro's own coordinates
  refuses that read; view-output policies remain unsupported. Table wildcards
  match physical source tables and do not target every view or built-in macro.
  Base-table protection applies through ordinary and secure views. Built-in macro
  bodies retain their existing call-name and dynamic-source checks.
- Deny detection is conservative before optimization. A view or CTE that projects
  a denied column can refuse even if the caller selects another column. No unused
  denied value is exposed to make that query run.
- Reads with pre-existing scan projections, extracted nested scan columns or
  other unsupported scan shapes refuse. Ordinary nested-value consumers can use
  masks only where the full source type and bindings are preserved.
- Writes and DDL targeting covered tables, INSERT included, refuse natively.
  Gateway checks also refuse writes and batches reading any CLS-covered table,
  including reads of unprotected columns. The removed gateway rewriter is no
  longer used as a write-safety oracle. No write-check policies are introduced.
- Existing historical-name restrictions remain. DuckLake change feeds refuse
  for native column-policy holders because they have no ordinary source scan to
  mask. File readers without table identity retain file-location authorization;
  they do not acquire name-scoped table policies.
- Gateway metadata filtering, routing and conservative syntax/write guards remain.
  Caller SQL bypasses column rewriting and normalization; the remaining guards
  still refuse syntax they cannot safely analyze. Gateway rewrite and schema-
  lookup metrics and their dashboard panels are removed.

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

Scala tests cover shared definition/import validation, scoped refusal of unusable
stored masks, snapshot invalidation, full policy scopes, large policy sets, byte
bounds, signing and verification, privileged callers, the kill switch and native-only transport. The cross-language
handle fixture pins the version transition.

At each DuckDB upgrade, revisit scan-column mappings, secure-view optimization, TRY,
independent binding, prepared-query lifecycle and descriptor behavior. These are
internal C++ interfaces, not a stable extension ABI.
