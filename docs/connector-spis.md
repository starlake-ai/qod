# Connector extension points

This page is for someone building a data connector outside this repository and linking it
into `qod-node`. Core keeps row and column policy enforcement; the connector keeps its own
remote protocol. The seams below let a linked connector honor the same policies without
core knowing anything about the connector.

## Native: the policy obligation (`ProtectedScan`)

Include `protected_scan.hpp`. A connector table function whose `get_table_entry` returns a
registered table implements `qod::ProtectedScan` on its bind data (`FunctionData`).

At `post_bind`, core runs `ApplyProtectedScans` for every principal, privileged ones
included, after column and row policies are applied. For each scan it:

1. Refuses the statement if a field the scan uses in its remote request (`FieldUses()`,
   kinds `Filter` and `VectorLineage`) has a column policy on it. `Projection` uses are
   ordinary projected columns and are covered by normal column policies. FieldUses are also
   reported to the describer as column reads.
2. Calls `AcceptRowPolicies` with the scan's bound row policies and the predicates.
3. Refuses a bounded scan whose `LoweringResult.exact` is false.
4. Seals the scan with a digest of the policies (`PolicyDigest`: SHA-256 over policy ids and
   SQL, in order).

`VerifyProtectedScans` runs again in `Enforce`, before optimization, and rechecks every seal
against the policies of the boundary above the scan.

### Predicate shape

`predicates` are the expressions of the row policy boundary filter, to be ANDed. Each one is
the OR of the matched policies, and each policy is wrapped as
`TRY(CAST(<policy> AS BOOLEAN))`. Column references bind to `get.table_index`; their
`column_index` indexes `get.GetColumnIds()`. The list is empty when no policy applies,
including for privileged principals. The scan is still sealed, with an empty policy list,
and the connector still enforces its own mandatory scope.

### Exactness

`exact = true` means the connector enforces exactly the rows for which the policy predicates
are TRUE, before any candidate limit it applies. A row where a policy evaluates to NULL, or
fails to evaluate, must not be returned. If the connector cannot guarantee that, return
`exact = false` with a `refusal_code` (lowercase `[a-z_]+`, for example `policy_unsupported`;
anything else is reported as that one code).

### Bounded and unbounded scans

`IsBounded()` is true when the connector applies a candidate limit (top-k) remotely. A local
filter above such a scan would change the answer, so exact lowering is mandatory and an
inexact result is refused. An unbounded scan may return `exact = false`: core keeps its local
row filter above the scan.

Connectors MUST register every table function that applies a remote candidate limit with
`RegisterBoundedFunction(name)` (the name is lowercased), whether or not its bind data is a
`ProtectedScan`. This is what makes core refuse a later `post_bind` that swaps in bind data
that is not a `ProtectedScan`: a `LogicalGet` of a registered bounded function without a
`ProtectedScan` is refused. Register before the database serves statements. Core also
registers the function of every bounded `ProtectedScan` it handles, so a swap in the same
or a later statement is refused even for a function the connector did not register, but a
function first planned through a swapped bind data is only covered by registration.

### Sealing

A connector MUST call `RequireSealed()` before any remote I/O. It throws a permission error
when core never sealed the scan. The seal must also survive plan copies: in
`FunctionData::Copy`, call `CopySealFrom(other)` so a copy of a sealed scan stays sealed
under the same digest.

## Native build

Link extra static extensions with:

```
cmake -S native/qod-node -B build/qod-node \
  -DQOD_EXTRA_EXTENSIONS="name=/path/libname.a;other=/path/libother.a" ...
```

Each entry is `name=path`. The archive must export
`int32_t duckdb_extension_<name>_describe(duckdb_extension_descriptor *)`. qod-node loads
every linked extension after each database open, before any SQL runs.

A linked extension's functions are unclassified by default and refused. Register them from
code compiled into qod-node with `RegisterLinkedFunctionClass(lower_name, type, class)`
(`linked_function_classes.hpp`), where `type` is scalar, aggregate or table. Registration is
only possible before `FreezeLinkedFunctionClasses()`, which `Serve` calls before it
installs authorization. After the freeze, for a name the compiled table already classifies,
for another type, or for a Superuser or File class, registration throws `std::logic_error`.

### Build marker

A linked build's `--build-info` marker carries a `;extra=<names>` suffix, only when extras
are linked. The open build's marker is unchanged. `scripts/release/qod-node-build-info.sh`
and `process_test.sh` parse the open marker, so release tooling for linked builds must
account for the suffix.

## Manager

Implement `ai.starlake.quack.spi.FederatedSourceProvider` and return it from
`ManagerModule.federatedSourceProviders`; return function classes for the linked node's
functions from `ManagerModule.functionClasses` as (name, type, class) with class allowed or
listing. Both are read right after the module is constructed, before `start()`, because pool
restore renders sources before modules start.

The provider supplies `wire` (the `sourceType` value, lowercase `[a-z0-9_]+`),
`extensionName`, `reservedSecretPrefixes`, `errors`, `normalizedConfig` and
`renderTemplate`. Its rendered SQL may contain `{{alias}}` and `{{secret.NAME}}`
placeholders; core substitutes them, the provider never does. A source of a plugin type
stores its config as raw JSON in the REST field `pluginConfig`; `config` stays for
`iceberg_rest` sources only.

Secrets: the provider's config is echoed back by the REST API, so it must hold secrets only
as `{{secret.NAME}}` placeholders, and the provider's `errors()` should reject literal
credentials.

Quoting: core refuses a plugin source whose rendered template has a `{{secret.NAME}}`
placeholder outside a single-quoted literal. There is no plain-token fallback as for legacy
`sql` sources; the source is skipped with a `PluginSecretUnquoted` error and the tenant-db's
other sources still attach. Core quotes only the secret values it substitutes. A provider
must itself quote and escape (double every `'`) every `pluginConfig` value it renders,
because tenant admins control that config and the node's init SQL runs with full privileges.

If a stored plugin source has no provider at boot (module absent), that source is skipped
with a `SourceProviderMissing` error; other sources are unaffected.

## Refusal messages

All start with `QoD: ` and never include policy SQL, literals, identity values or backend
text.

- `QoD: <refusal_code>: a row policy cannot be enforced before the connector's candidate limit`
- `QoD: a bounded connector scan without a policy obligation is not supported`
- `QoD: a connector scan without a table entry is not supported`
- `QoD: connector scan ran without a sealed policy obligation`
- `QoD: connector scan policy obligation could not be verified`
- `QoD: field_denied: a connector scan uses a column that has a column policy`
