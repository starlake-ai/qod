import {
  checkEditorModule, checkEditorUnmount, type SourceEditorContext, type Unmount,
} from './sourceEditorContract';

export interface StartEditorOptions {
  /** Imports the bundle; the console passes `() => import(bundle)`. */
  load: () => Promise<unknown>;
  /** An element of this editor run's own: a late run never writes into another's. */
  el: HTMLElement;
  module: string;
  ctx: SourceEditorContext;
  onError: (message: string) => void;
}

/** Loads one editor bundle and mounts it into `el` (contract 1); returns the function that stops
 *  it. A bundle that fails to load, speaks another contract, throws in mount or returns no unmount
 *  function reports one error naming its module and leaves `el` empty; nothing it does throws out
 *  of here, so the rest of the form works on. A stop before the mount settles unmounts the editor
 *  as soon as it does, and silences its errors; the editor's save and cancel then do nothing. */
export function startSourceEditor(o: StartEditorOptions): () => void {
  let stopped = false;
  let unmount: Unmount | null = null;
  // After a stop the editor may still call its context (a save in flight, a late click): a save
  // then reports an error without reaching core's, a cancel does nothing.
  const ctx: SourceEditorContext = {
    ...o.ctx,
    save: (pluginConfig, secrets) => stopped
      ? Promise.resolve({ ok: false, errors: ['the editor was closed'] })
      : o.ctx.save(pluginConfig, secrets),
    cancel: () => { if (!stopped) o.ctx.cancel(); },
  };
  const fail = (message: string) => {
    o.el.replaceChildren();
    if (!stopped) o.onError(message);
  };
  const run = async (mod: unknown) => {
    if (stopped) return;
    const checked = checkEditorModule(mod, o.module);
    if (!checked.ok) { fail(checked.error); return; }
    let returned: unknown;
    try {
      returned = await checked.mount(o.el, ctx);
    } catch (e) {
      fail(`module ${o.module}: the source editor failed to mount (${messageOf(e)})`);
      return;
    }
    const u = checkEditorUnmount(returned, o.module);
    if (!u.ok) { fail(u.error); return; }
    if (stopped) { safely(u.unmount); return; }
    unmount = u.unmount;
  };
  Promise.resolve()
    .then(() => o.load())
    .then(run, e => {
      if (!stopped) o.onError(`module ${o.module}: the source editor failed to load (${messageOf(e)})`);
    })
    .catch(e => fail(`module ${o.module}: the source editor failed (${messageOf(e)})`));
  return () => {
    stopped = true;
    const u = unmount;
    unmount = null;
    if (u) safely(u);
  };
}

function safely(f: Unmount): void {
  try { f(); } catch (e) { console.error(e); }
}

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
