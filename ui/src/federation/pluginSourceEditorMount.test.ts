import { describe, expect, it } from 'vitest';
import { editorMountDeps } from './PluginSourceEditor';
import { startSourceEditor } from './sourceEditorHost';
import type { SourceEditorContext } from './sourceEditorContract';

const settle = () => new Promise<void>(r => setTimeout(r, 0));

const base = {
  sourceType: 'type_a', bundle: '/b.js', module: 'm', tenant: 't', tenantDb: 'db',
  source: null as { id: string } | null, readOnly: false,
};

const same = (a: unknown[], b: unknown[]) => a.length === b.length && a.every((v, i) => Object.is(v, b[i]));

/** Mimics React's effect: runs `start` when the deps change, stopping the previous run first. */
function fakeEffect(start: () => () => void) {
  let deps: unknown[] | null = null;
  let stop: (() => void) | null = null;
  return {
    render(next: unknown[]) {
      if (deps && same(deps, next)) return;
      stop?.();
      deps = next;
      stop = start();
    },
    close() { stop?.(); stop = null; deps = null; },
  };
}

describe('editorMountDeps', () => {
  it('differs when only the source type changes (same module, bundle, no source id)', () => {
    expect(same(editorMountDeps(base), editorMountDeps({ ...base, sourceType: 'type_b' }))).toBe(false);
    expect(same(editorMountDeps(base), editorMountDeps({ ...base }))).toBe(true);
  });

  it('remounts the editor on a type switch and unmounts it on close', async () => {
    let mounts = 0;
    let unmounts = 0;
    const mod = {
      qodSourceEditorContract: 1,
      mount: () => { mounts += 1; return () => { unmounts += 1; }; },
    };
    const el = { replaceChildren: () => {} } as unknown as HTMLElement;
    const effect = fakeEffect(() => startSourceEditor({
      load: async () => mod, el, module: 'm', ctx: {} as SourceEditorContext, onError: () => {},
    }));
    effect.render(editorMountDeps(base));
    await settle();
    expect(mounts).toBe(1);
    effect.render(editorMountDeps({ ...base, sourceType: 'type_b' }));
    await settle();
    expect(unmounts).toBe(1);
    expect(mounts).toBe(2);
    effect.close();
    expect(unmounts).toBe(2);
  });
});
