import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import type {
  FederatedSecretResponse, FederatedSourceCreateRequest, FederatedSourceResponse,
} from '../api/types';
import {
  checkEditorUnmount, editorReadOnly, sourceEditorContext, type SourceEditorContext,
} from './sourceEditorContract';
import { startSourceEditor } from './sourceEditorHost';
import { savePluginSource, type PluginSaveApi } from './pluginSourceSave';

/** Every fake below settles on microtasks only, so one macrotask tick lets a mount finish. */
const settle = () => new Promise<void>(r => setTimeout(r, 0));

const user = { username: 'alice', superuser: false, permissions: [] as string[] };

const stored: FederatedSourceResponse = {
  id: 'fs-1', tenantDbId: 'tdb-1', alias: 'docs', disabled: false,
  sourceType: 'test_connector', readOnly: true, pluginConfig: { collection: 'docs' },
};

function fakeElement() {
  const state = { cleared: 0 };
  const el = { replaceChildren: () => { state.cleared += 1; } } as unknown as HTMLElement;
  return { el, state };
}

/** A fake editor bundle speaking contract 1 that records what core hands it. */
function fakeEditor() {
  const seen: { el?: HTMLElement; ctx?: SourceEditorContext; unmounts: number } = { unmounts: 0 };
  const mod = {
    qodSourceEditorContract: 1,
    mount(el: HTMLElement, ctx: SourceEditorContext) {
      seen.el = el;
      seen.ctx = ctx;
      return () => { seen.unmounts += 1; };
    },
  };
  return { mod, seen };
}

function ctxWith(over: Partial<Parameters<typeof sourceEditorContext>[0]> = {}): SourceEditorContext {
  return sourceEditorContext({
    tenant: 'acme', tenantDb: 'acme_default', user, source: null, readOnly: false, heldSecrets: [],
    save: async () => ({ ok: true }), cancel: () => {}, ...over,
  });
}

function start(load: () => Promise<unknown>, ctx: SourceEditorContext = ctxWith()) {
  const { el, state } = fakeElement();
  const errors: string[] = [];
  const stop = startSourceEditor({ load, el, module: 'test-module', ctx, onError: m => errors.push(m) });
  return { el, state, errors, stop };
}

/** The REST calls of a save, in order, against a fake manager. `secrets` is what the source holds,
 *  or 'missing' when it does not exist yet (its secret listing answers 404). */
function recordingApi(secrets: FederatedSecretResponse[] | 'missing') {
  const calls: string[] = [];
  const creates: FederatedSourceCreateRequest[] = [];
  const api: PluginSaveApi = {
    async createFederatedSource(_t, _d, req) {
      calls.push(req.disabled ? 'create disabled' : 'create');
      creates.push(req);
      return stored;
    },
    async listFederatedSecrets() {
      calls.push('list');
      if (secrets === 'missing') throw new ApiError(404, 'not found', 'not_found');
      return { secrets };
    },
    async upsertFederatedSecret(_t, _d, _a, req) {
      calls.push(`secret ${req.name}=${req.value}`);
      return { id: 's-1', federatedSourceId: 'fs-1', name: req.name };
    },
    async deleteFederatedSecret(_t, _d, _a, name) { calls.push(`delete ${name}`); },
  };
  return { api, calls, creates };
}

function savingCtx(api: PluginSaveApi, over: Partial<Parameters<typeof sourceEditorContext>[0]> = {}) {
  return ctxWith({
    save: (pluginConfig, secrets, options) => savePluginSource(api, {
      tenant: 'acme', tenantDb: 'acme_default', alias: ' Docs ', description: ' Product docs ',
      sourceType: 'test_connector', pluginConfig, secrets, connectionChanged: options?.connectionChanged,
    }),
    ...over,
  });
}

describe('source editor contract 1', () => {
  it('mounts a fake editor with the context core builds, and unmounts it on stop', async () => {
    const { mod, seen } = fakeEditor();
    const { el, errors, stop } = start(async () => mod);
    await settle();
    expect(errors).toEqual([]);
    expect(seen.el).toBe(el);
    expect(seen.ctx).toMatchObject({
      contract: 1, apiBase: '/api', tenant: 'acme', tenantDb: 'acme_default', user,
      source: null, readOnly: false, theme: 'dark',
    });
    expect(Object.keys(seen.ctx!).sort()).toEqual([
      'apiBase', 'cancel', 'contract', 'heldSecrets', 'readOnly', 'save', 'source', 'tenant', 'tenantDb', 'theme', 'user',
    ]);
    stop();
    expect(seen.unmounts).toBe(1);
  });

  it('accepts a mount that resolves to its unmount function', async () => {
    let unmounts = 0;
    const { errors, stop } = start(async () => ({
      qodSourceEditorContract: 1,
      mount: async () => () => { unmounts += 1; },
    }));
    await settle();
    expect(errors).toEqual([]);
    stop();
    expect(unmounts).toBe(1);
  });

  it('unmounts an editor whose mount settles after the form closed', async () => {
    let resolveMount!: (u: () => void) => void;
    let unmounts = 0;
    const { errors, stop } = start(async () => ({
      qodSourceEditorContract: 1,
      mount: () => new Promise<() => void>(r => { resolveMount = r; }),
    }));
    await settle();
    stop();
    resolveMount(() => { unmounts += 1; });
    await settle();
    expect(unmounts).toBe(1);
    expect(errors).toEqual([]);
  });
});

describe('ctx.save', () => {
  it('creates a new source, then writes its secret, then enables it', async () => {
    const rec = recordingApi('missing');
    const { mod, seen } = fakeEditor();
    start(async () => mod, savingCtx(rec.api));
    await settle();
    const result = await seen.ctx!.save({ collection: 'docs' }, [{ name: 'TC_TOKEN', value: 'k1' }]);
    expect(result).toEqual({ ok: true });
    expect(rec.calls).toEqual(['list', 'create disabled', 'secret TC_TOKEN=k1', 'create']);
    expect(rec.creates[1]).toEqual({
      alias: 'docs', description: 'Product docs', sourceType: 'test_connector', readOnly: true,
      pluginConfig: { collection: 'docs' },
    });
    expect(rec.creates[0]).toEqual({ ...rec.creates[1], disabled: true });
  });

  it('saves an existing source, then writes the secret it holds', async () => {
    const rec = recordingApi([
      { id: 's-1', federatedSourceId: 'fs-1', name: 'TC_TOKEN', value: '***REDACTED***' },
    ]);
    const { mod, seen } = fakeEditor();
    start(async () => mod, savingCtx(rec.api, { source: stored }));
    await settle();
    const result = await seen.ctx!.save({ collection: 'docs' }, [{ name: 'TC_TOKEN', value: 'k2' }]);
    expect(result).toEqual({ ok: true });
    expect(rec.calls).toEqual(['list', 'create', 'secret TC_TOKEN=k2']);
  });

  it('saves a source with no secret in one call', async () => {
    const rec = recordingApi('missing');
    const { mod, seen } = fakeEditor();
    start(async () => mod, savingCtx(rec.api));
    await settle();
    expect(await seen.ctx!.save({ collection: 'docs' }, [])).toEqual({ ok: true });
    expect(rec.calls).toEqual(['create']);
  });

  it('saves a connection change disabled first, through the editor third argument', async () => {
    const rec = recordingApi([
      { id: 's-1', federatedSourceId: 'fs-1', name: 'TC_TOKEN', value: '***REDACTED***' },
    ]);
    const { mod, seen } = fakeEditor();
    start(async () => mod, savingCtx(rec.api, { source: stored }));
    await settle();
    const result = await seen.ctx!.save(
      { collection: 'docs' }, [{ name: 'TC_TOKEN', value: 'k3' }], { connectionChanged: true },
    );
    expect(result).toEqual({ ok: true });
    expect(rec.calls).toEqual(['list', 'create disabled', 'secret TC_TOKEN=k3', 'create']);
  });
});

describe('ctx.readOnly', () => {
  const held: FederatedSecretResponse = { id: 's-1', federatedSourceId: 'fs-1', name: 'TC_TOKEN', value: '***REDACTED***' };
  const refd: FederatedSecretResponse = { id: 's-2', federatedSourceId: 'fs-1', name: 'TC_REF', externalRef: 'env:QOD_TC' };

  it('follows core rule: secrets held, a caller who is not a superuser, a type that does not opt in', () => {
    expect(editorReadOnly(stored, [held], false, false)).toBe(true);
    expect(editorReadOnly(stored, [], false, false)).toBe(false);
    expect(editorReadOnly(stored, [held], true, false)).toBe(false);
    expect(editorReadOnly(null, [], false, false)).toBe(false);
    // A type that opts in: editable while no secret is held through an externalRef.
    expect(editorReadOnly(stored, [held], false, true)).toBe(false);
    expect(editorReadOnly(stored, [held, refd], false, true)).toBe(true);
    expect(editorReadOnly(stored, [held, refd], true, true)).toBe(false);
    expect(editorReadOnly(null, [], false, true)).toBe(false);
  });

  it('reaches the editor of a non-superuser once the source holds a secret', async () => {
    const { mod, seen } = fakeEditor();
    start(async () => mod, ctxWith({ source: stored, readOnly: editorReadOnly(stored, [held], false, false) }));
    await settle();
    expect(seen.ctx!.readOnly).toBe(true);
    expect(seen.ctx!.source).toBe(stored);
  });
});

describe('ctx.heldSecrets', () => {
  it('hands the editor the names of the stored secrets, and none for a new source', async () => {
    const a = fakeEditor();
    start(async () => a.mod, ctxWith({ source: stored, heldSecrets: ['TC_TOKEN'] }));
    await settle();
    expect(a.seen.ctx!.heldSecrets).toEqual(['TC_TOKEN']);
    const b = fakeEditor();
    start(async () => b.mod, ctxWith());
    await settle();
    expect(b.seen.ctx!.heldSecrets).toEqual([]);
  });
});

describe('a failing editor is contained', () => {
  it('names the module of a bundle that fails to load', async () => {
    const { errors } = start(() => Promise.reject(new Error('Failed to fetch dynamically imported module')));
    await settle();
    expect(errors).toEqual([
      'module test-module: the source editor failed to load (Failed to fetch dynamically imported module)',
    ]);
  });

  it('names the module of a loader that throws', async () => {
    const { errors } = start(() => { throw new Error('bad url'); });
    await settle();
    expect(errors).toEqual(['module test-module: the source editor failed to load (bad url)']);
  });

  it('names the contract a bundle speaks, or what it lacks', async () => {
    const cases: [unknown, string][] = [
      [{ qodSourceEditorContract: 2, mount: () => () => {} },
        'module test-module: this source editor needs editor contract 2; this console supports 1'],
      [{ mount: () => () => {} }, 'module test-module: the source editor exports no qodSourceEditorContract'],
      [{ qodSourceEditorContract: 1 }, 'module test-module: the source editor exports no mount function'],
      [null, 'module test-module: the source editor exports no qodSourceEditorContract'],
    ];
    for (const [mod, error] of cases) {
      const { errors } = start(async () => mod);
      await settle();
      expect(errors).toEqual([error]);
    }
  });

  it('empties the editor element when mount throws or rejects', async () => {
    for (const mount of [() => { throw new Error('boom'); }, async () => { throw new Error('boom'); }]) {
      const { errors, state } = start(async () => ({ qodSourceEditorContract: 1, mount }));
      await settle();
      expect(errors).toEqual(['module test-module: the source editor failed to mount (boom)']);
      expect(state.cleared).toBe(1);
    }
  });

  it('refuses a mount that returns no unmount function', async () => {
    const { errors, state } = start(async () => ({ qodSourceEditorContract: 1, mount: () => undefined }));
    await settle();
    expect(errors).toEqual(["module test-module: the source editor's mount did not return an unmount function"]);
    expect(state.cleared).toBe(1);
    for (const v of [undefined, null, 1, 'x', {}]) expect(checkEditorUnmount(v, 'test-module').ok).toBe(false);
  });

  it('reports nothing once the form closed', async () => {
    let rejectLoad!: (e: unknown) => void;
    const { errors, stop } = start(() => new Promise((_, reject) => { rejectLoad = reject; }));
    await settle(); // the loader runs on a microtask; let it start before the form closes
    stop();
    rejectLoad(new Error('late'));
    await settle();
    expect(errors).toEqual([]);
  });

  it('keeps another editor working after one fails', async () => {
    const failing = start(() => Promise.reject(new Error('gone')));
    const { mod, seen } = fakeEditor();
    const working = start(async () => mod);
    await settle();
    expect(failing.errors).toHaveLength(1);
    expect(working.errors).toEqual([]);
    expect(seen.ctx?.contract).toBe(1);
  });
});

describe('startSourceEditor after a stop', () => {
  it('answers save with an error without calling core, and ignores cancel', async () => {
    const { mod, seen } = fakeEditor();
    let saves = 0;
    let cancels = 0;
    const ctx = ctxWith({
      save: async () => { saves += 1; return { ok: true }; },
      cancel: () => { cancels += 1; },
    });
    const { stop } = start(async () => mod, ctx);
    await settle();
    await seen.ctx!.save({}, []);
    seen.ctx!.cancel();
    expect([saves, cancels]).toEqual([1, 1]);
    stop();
    const r = await seen.ctx!.save({}, []);
    seen.ctx!.cancel();
    expect(r).toEqual({ ok: false, errors: ['the editor was closed'] });
    expect([saves, cancels]).toEqual([1, 1]);
  });
});
