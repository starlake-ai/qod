import type { FederatedSourceResponse } from '../api/types';

/** Source editor contract 1. A module's editor bundle for one of its federated source types
 *  exports `qodSourceEditorContract = 1` and `mount(el, ctx)`, returning its unmount function (or a
 *  Promise of it). Core keeps the alias, description, read-only flag, list, delete and secrets
 *  panel; the editor owns the type's own fields and hands its config and secrets to ctx.save. */
export const SOURCE_EDITOR_CONTRACT = 1;

export type Unmount = () => void;

export interface SourceEditorSecret { name: string; value: string }

export type SaveResult = { ok: true } | { ok: false; errors: string[] };

export interface SourceEditorContext {
  contract: 1;
  apiBase: string;
  tenant: string;
  tenantDb: string;
  user: { username: string; superuser: boolean; permissions: string[] };
  /** The stored source when editing, null when creating. */
  source: FederatedSourceResponse | null;
  /** True when core's rule forbids the caller from changing the source: it holds secrets and the
   *  caller is not a superuser. */
  readOnly: boolean;
  /** Core's own save: the source with `readOnly: true` and this config, then each secret. */
  save(pluginConfig: unknown, secrets: SourceEditorSecret[]): Promise<SaveResult>;
  /** Closes the form without saving. */
  cancel(): void;
  theme: 'dark';
}

export type EditorMountFn = (el: HTMLElement, ctx: SourceEditorContext) => Unmount | Promise<Unmount>;
export type EditorCheck = { ok: true; mount: EditorMountFn } | { ok: false; error: string };
export type EditorUnmountCheck = { ok: true; unmount: Unmount } | { ok: false; error: string };

/** Checks a loaded bundle against contract 1; the error names the module. */
export function checkEditorModule(mod: unknown, module: string): EditorCheck {
  const m = (mod ?? {}) as { qodSourceEditorContract?: unknown; mount?: unknown };
  if (m.qodSourceEditorContract === undefined)
    return { ok: false, error: `module ${module}: the source editor exports no qodSourceEditorContract` };
  if (m.qodSourceEditorContract !== SOURCE_EDITOR_CONTRACT)
    return {
      ok: false,
      error: `module ${module}: this source editor needs editor contract ${String(m.qodSourceEditorContract)}; this console supports ${SOURCE_EDITOR_CONTRACT}`,
    };
  if (typeof m.mount !== 'function')
    return { ok: false, error: `module ${module}: the source editor exports no mount function` };
  return { ok: true, mount: m.mount as EditorMountFn };
}

/** Checks what an editor's mount resolved to: contract 1 requires an unmount function. */
export function checkEditorUnmount(value: unknown, module: string): EditorUnmountCheck {
  return typeof value === 'function'
    ? { ok: true, unmount: value as Unmount }
    : { ok: false, error: `module ${module}: the source editor's mount did not return an unmount function` };
}

/** Core's rule (FederatedSourceHandlers.changesHeldSource): a source holding secrets is changed by
 *  a superuser only. A new source holds none. */
export function editorReadOnly(
  source: FederatedSourceResponse | null, secretCount: number, superuser: boolean,
): boolean {
  return source != null && secretCount > 0 && !superuser;
}

export interface SourceEditorContextInput {
  tenant: string;
  tenantDb: string;
  user: SourceEditorContext['user'];
  source: FederatedSourceResponse | null;
  readOnly: boolean;
  save: SourceEditorContext['save'];
  cancel: () => void;
}

/** The context an editor is mounted with, contract 1. */
export function sourceEditorContext(p: SourceEditorContextInput): SourceEditorContext {
  return {
    contract: 1,
    apiBase: '/api',
    tenant: p.tenant,
    tenantDb: p.tenantDb,
    user: p.user,
    source: p.source,
    readOnly: p.readOnly,
    save: p.save,
    cancel: p.cancel,
    theme: 'dark',
  };
}
