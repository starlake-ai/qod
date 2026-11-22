import type { ConsolePageView } from '../api/types';

/** Console page contract 1 (issue #274). A module page bundle exports
 *  `qodConsoleContract = 1` and `mount(el, ctx)`, returning its unmount function (or a Promise of
 *  it). The console's router owns history: a page navigates through ctx.navigate. */
export const CONSOLE_CONTRACT = 1;

export type Unmount = () => void;

export interface PageContext {
  contract: 1;
  apiBase: string;
  basePath: string;
  user: { username: string; superuser: boolean; permissions: string[] };
  subpath: string;
  onLocationChange(cb: (subpath: string) => void): () => void;
  navigate(path: string): void;
  theme: 'dark';
  onUnauthorized(): void;
}

export type MountFn = (el: HTMLElement, ctx: PageContext) => Unmount | Promise<Unmount>;
export type CheckResult = { ok: true; mount: MountFn } | { ok: false; error: string };

/** Checks a loaded bundle against contract 1; the error names the module. */
export function checkPageModule(mod: unknown, module: string): CheckResult {
  const m = (mod ?? {}) as { qodConsoleContract?: unknown; mount?: unknown };
  if (m.qodConsoleContract === undefined)
    return { ok: false, error: `module ${module}: the page exports no qodConsoleContract` };
  if (m.qodConsoleContract !== CONSOLE_CONTRACT)
    return {
      ok: false,
      error: `module ${module}: this page needs console contract ${String(m.qodConsoleContract)}; this console supports ${CONSOLE_CONTRACT}`,
    };
  if (typeof m.mount !== 'function')
    return { ok: false, error: `module ${module}: the page exports no mount function` };
  return { ok: true, mount: m.mount as MountFn };
}

/** The part of the router path below the page: '' at its root, else with a leading slash. */
export function subpathOf(routerPathname: string, routerBase: string): string {
  const rest = routerPathname.startsWith(routerBase) ? routerPathname.slice(routerBase.length) : '';
  return rest === '/' ? '' : rest;
}

/** Router target of ctx.navigate: a /ui path is a console path, anything else is page-relative. */
export function resolveNavigate(routerBase: string, path: string): string {
  if (path === '/ui') return '/';
  if (path.startsWith('/ui/')) return path.slice(3);
  const rel = path.replace(/^\/+/, '');
  return rel === '' ? routerBase : `${routerBase}/${rel}`;
}

/** The page a route names, among the pages the caller may see; undefined redirects. */
export function findPage(
  pages: ConsolePageView[], module: string | undefined, page: string | undefined,
): ConsolePageView | undefined {
  return pages.find(p => p.module === module && p.id === page);
}
