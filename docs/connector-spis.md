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
   reported to the describer as column reads. `FieldUse.column` must use the column's
   spelling in the table entry, since that is the name policies and reads are matched on.
2. Calls `AcceptRowPolicies` with the scan's bound row policies and the predicates.
3. Refuses a bounded scan whose `LoweringResult.exact` is false.
4. Seals the scan with a digest of the policies (`PolicyDigest`: SHA-256 over policy ids and
   SQL, in order).

`VerifyProtectedScans` runs again in `Enforce`, before optimization, and rechecks every seal
against the policies of the boundary above the scan.

Bind runs before core has handed the scan its policies, so the table function's bind MUST
perform no remote I/O. A connector scan on an internal (non-quack) connection is never
sealed, because core runs the obligation only for quack sessions, so it always refuses at
`RequireSealed()`.

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
fails to evaluate, must not be returned. Virtual columns such as scores or ranks must not
depend on rows the policy excludes: a rank computed over the whole remote set reveals that
hidden rows exist. If the connector cannot guarantee that, return
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
for a type other than scalar, aggregate or table, or for a Superuser or File class, registration throws `std::logic_error`.

### Build marker

A linked build's `--build-info` output carries a `;extra=<names>` suffix, only when extras
are linked. The open build's marker is unchanged. The suffix is appended at run time, so the
literal compiled into the binary is the base marker in both builds:
`scripts/release/qod-node-build-info.sh` greps the binary's bytes, finds that base literal
in a linked build and never sees `;extra=`. Only a parser of `--build-info` output anchored
at the end of the line (such as `process_test.sh`) rejects the suffix, so tooling that runs a
linked binary must accept it.

## Manager

Implement `ai.starlake.quack.spi.FederatedSourceProvider` and return it from
`ManagerModule.federatedSourceProviders`; return function classes for the linked node's
functions from `ManagerModule.functionClasses` as (name, type, class) with class allowed or
listing. Both are read right after the module is constructed, before `start()`, because pool
restore renders sources before modules start. A function classed allowed this way becomes
usable in row-policy predicates, like a built-in allowed function.

The provider supplies `wire` (the `sourceType` value, lowercase `[a-z0-9_]+`, at most 32
characters), `extensionName`, `reservedSecretPrefixes`, `errors`, `normalizedConfig` and
`renderTemplate`. `extensionName` is informational in this release: core does not load or
check it. Its rendered SQL may contain `{{alias}}` and `{{secret.NAME}}`
placeholders; core substitutes them, the provider never does. A source of a plugin type
stores its config as raw JSON in the REST field `pluginConfig`; `config` stays for
`iceberg_rest` sources only. From the CLI: `qod federation create <tenant> <db> --alias <alias>
--type <wire> --plugin-config '<json object>'`.

Secrets: the provider's config is echoed back by the REST API, so it must hold secrets only
as `{{secret.NAME}}` placeholders, and the provider's `errors()` should reject literal
credentials.

Ambient credentials: there is no superuser-only hook for plugin types, so any tenant admin
who may write sources can create one. A provider must refuse in `errors()` any config whose
ATTACH would use the node's ambient credentials (environment, instance role, credential
chain, local files) instead of the source's own secrets.

Editing a source that holds secrets: by default only a superuser changes the connection or config
of a source holding secrets, or makes a read-only one writable. A provider lifts that rule for its
type by overriding both `editsHeldSources = true` and `connectionOf(raw)`, which names what the
source's secrets are bound to (an endpoint and the secret name the config uses, for example) for a
config that parses, and None otherwise. For a source of such a type whose secrets are all held as
values, a write whose config names the same connection keeps the secrets and is open to anyone who
may write the source. A write naming another connection is refused `409
connection_change_requires_disabled` unless it saves the source disabled, for every caller,
superusers included; that disabled write deletes every secret of the source in the same
transaction, audited `federation.source.upsert` `ok` with detail `cleared` (`secrets: <n>`). A
read-only source still stays read-only for a non-superuser. A held `externalRef` secret, a config
`connectionOf` cannot name, or a provider that is not loaded keeps the superuser rule. Accepted
risk: whoever edits such a source may point it at anything the stored secret reaches on the same
connection, so return in the identity everything a secret is bound to.

Quoting: core refuses a plugin source whose rendered template has a `{{secret.NAME}}`
placeholder outside a single-quoted literal. There is no plain-token fallback as for legacy
`sql` sources; the source is skipped with a `PluginSecretUnquoted` error and the tenant-db's
other sources still attach. Core quotes only the secret values it substitutes. A provider
must itself quote and escape (double every `'`) every `pluginConfig` value it renders,
because tenant admins control that config and the node's init SQL runs with full privileges.

If a stored plugin source has no provider at boot (module absent), that source is skipped
with a `SourceProviderMissing` error; other sources are unaffected.

### Console editor

A provider may name a console editor for its type: `override def editor =
Some(SourceEditor(label, bundle))`. `bundle` is an origin-relative path of `[A-Za-z0-9._-]`
segments under one of the module's own `staticMounts` (a root mount covers none). It is checked
at boot after `start()`, like a console page's bundle; a refused editor fails the boot with a
message naming the module. The federation form's type picker lists module types after the four
built-in ones, as `<sourceType>: <label>`; a type without an editor is listed too, and the form
says "this source type has no editor; use the REST API". Editing a stored module source mounts
the type's editor too, and the editor remounts when the source type changes.

`GET /api/federated-source-types` (any admin session) answers
`{types: [{module, sourceType, label, editorBundle, editsHeldSources}]}`, with `label` the
editor's (the wire when there is no editor), `editorBundle` null for a type without an editor, and
`editsHeldSources` the provider's flag.

Editor contract 1: the bundle exports `qodSourceEditorContract = 1` and `mount(el, ctx)`,
returning an unmount function or a Promise of one. A bundle that fails to load, speaks another
contract, throws in `mount` or returns no unmount function shows an error naming its module; the
rest of the form works on. Core keeps the alias, description, read-only flag, list, delete and
secrets panel; the editor replaces only the type's own fields. `ctx` holds `contract: 1`,
`apiBase: '/api'`, `tenant`, `tenantDb`, `user` (`username`, `superuser`, `permissions`),
`source` (the stored source when editing, null when creating), `readOnly` (true when the source
holds secrets, the caller is not a superuser, and either the type does not set `editsHeldSources`
or a secret is held through an `externalRef`, so the manager would refuse a change),
`heldSecrets` (the names of the source's stored secrets, empty when creating),
`save(pluginConfig, secrets, options?)`, `cancel()` and `theme: 'dark'`. `save` stores
`{alias, description, sourceType, readOnly: true, pluginConfig}` and then each `{name, value}`
secret, and resolves to `{ok: true}` or `{ok: false, errors}`. Keep secret values out of
`pluginConfig`: reference them as `{{secret.NAME}}` placeholders and hand the values to `save`.

The save sequence never leaves an enabled source naming a secret it was handed. A new source, or an
existing one holding no secrets, is saved disabled, then every secret is written, then the source
is saved as asked. If a secret fails, the source stays disabled and the error says so and names
the secret to set; if the final save is refused, the error ends with "the source remains
disabled". An existing source that already holds secrets is saved first and its secrets after;
secrets new to it are written first and deleted again if the source is refused. A secret held
through an `externalRef` is never overwritten with a value.

`save(pluginConfig, secrets, {connectionChanged: true})` says the edit changes what the source's
secrets are bound to (the provider's `connectionOf`). For an existing source holding secrets the
sequence is then the disabled-first one: the source is saved disabled, which deletes its stored
secrets, every secret handed to `save` is written, then the source is saved as asked. The editor
must hand again every secret the source needs; with none, `save` answers `{ok: false, errors:
['changing the connection clears the stored secrets: type them again']}` and saves nothing. The
third argument is optional (false when omitted), so an editor that passes two saves as before.

`errors` carries each failure whole. The manager answers a refused source with one
`invalid_federated_source` message that joins its errors with "; ", and a provider error may itself
contain "; ", so `save` returns that message as one entry and never splits it. An editor that
wants per-field errors validates through its own module endpoint before saving.

### Writing an editor

- The editor is mounted inside core's `<form>`. Do not nest a `<form>`, give every button
  `type="button"`, and know that Enter in a field submits core's form, which is a no-op.
- Core does not display a failed save's errors; the editor must show the `errors` it gets back.
- On `{ok: true}` core closes the form and unmounts the editor. After the editor is unmounted, a
  `save` resolves `{ok: false, errors: ['the editor was closed']}` without saving, and `cancel` does
  nothing.
- The bundle runs same-origin with the caller's admin session, so it carries the module's trust:
  it can call any `/api` route the caller can.
- `ctx.source.pluginConfig` holds secret placeholders (`{{secret.NAME}}`), never values.
- Every module source is saved `readOnly: true`: core cannot judge what a plugin's writes do, so it
  never offers them. The `ctx.readOnly` rule is conservative: it is true
  for a non-superuser editing a source that holds any secret, whether or not the edit would touch it,
  unless the type sets `editsHeldSources` and no secret is held through an `externalRef`.
- Contract 1 grows only additively: `heldSecrets` and the third `save` argument are new, and an
  older console sends neither, so check `Array.isArray(ctx.heldSecrets)` before relying on them.
- Saving an existing source that holds no secret, with new secrets, disables it until those
  secrets are written, then saves it as asked.

Authorization of a module's own routes about a tenant-db's sources (a validation or discovery
endpoint the editor calls): core's rule for creating a module-provided source is the REST guard
that every non-public `/api` route passes, plus
`TenantScopeCheck.reject(apiKey, tenant)(ctx.scopeOf)`, the call
`FederatedSourceHandlers.createSource` makes with the same resolver. A module route under `/api`
gets the guard; the module makes the call itself. The guard reads no tenant from a module's path,
so that call is what confines the caller to the tenant.

Reading a stored source from a module route: `ctx.ownSources` answers `source(tenantDbId, alias)`,
the stored source when its type is one of the module's own provider wires (None for any other
type or an unknown alias), and `secretValue(tenantDbId, alias, name)`, the value of its secret
when it is held as a value (None for an `externalRef` secret or a missing one). Both read the
control-plane database: call them inside `IO.blocking`, once the route has authorized its caller
as above. Take the endpoint from the stored config, never from the request, never log the value,
and send it only to that endpoint.

## Refusal messages

All start with `QoD: ` and never include policy SQL, literals, identity values or backend
text.

- `QoD: <refusal_code>: a row policy cannot be enforced before the connector's candidate limit`
- `QoD: a bounded connector scan without a policy obligation is not supported`
- `QoD: a connector scan without a table entry is not supported`
- `QoD: connector scan ran without a sealed policy obligation`
- `QoD: connector scan policy obligation could not be verified`
- `QoD: field_denied: a connector scan uses a column that has a column policy`
