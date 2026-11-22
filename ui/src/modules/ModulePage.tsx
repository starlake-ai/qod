import { useEffect, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { ConsolePageView } from '../api/types';
import {
  checkPageModule, checkUnmount, findPage, resolveNavigate, subpathOf, type PageContext, type Unmount,
} from './pageContract';

/** Renders a module's console page (contract 1) inside the console layout. A page the caller
 *  cannot see redirects to `landing`; a page that fails to load, speaks another contract, throws
 *  in mount or returns no unmount function shows an error panel naming its module, and the rest of
 *  the console works on. */
export default function ModulePage({ pages, landing, loading = false }: {
  pages: ConsolePageView[];
  landing: string;
  loading?: boolean;
}) {
  const { module, page } = useParams();
  const entry = findPage(pages, module, page);
  const host = useRef<HTMLDivElement>(null);
  // The current run's subscribers; each mount run has a Set of its own.
  const listeners = useRef(new Set<(s: string) => void>());
  const [error, setError] = useState<string | null>(null);
  const location = useLocation();
  const nav = useNavigate();
  const { username, superuser, permissions, logout } = useAuth();
  const routerBase = `/x/${module}/${page}`;
  const subpath = subpathOf(location.pathname, routerBase);
  // The page keeps its context for its whole life; these refs let it reach the current subpath,
  // router and session functions.
  const subpathRef = useRef(subpath);
  subpathRef.current = subpath;
  const navRef = useRef(nav);
  navRef.current = nav;
  const logoutRef = useRef(logout);
  logoutRef.current = logout;

  useEffect(() => {
    listeners.current.forEach(cb => {
      try { cb(subpath); } catch (e) { console.error(e); }
    });
  }, [subpath]);

  useEffect(() => {
    const container = host.current;
    if (!entry || !container) return;
    // Each run mounts into an element of its own, so a cancelled run that finishes late can
    // never write to, or blank, the element of the page shown now.
    const el = document.createElement('div');
    container.appendChild(el);
    const subscribers = new Set<(s: string) => void>();
    listeners.current = subscribers;
    let unmount: Unmount | null = null;
    let cancelled = false;
    setError(null);
    const ctx: PageContext = {
      contract: 1,
      apiBase: '/api',
      basePath: entry.path,
      user: { username: username ?? '', superuser, permissions },
      get subpath() { return subpathRef.current; },
      onLocationChange(cb) {
        if (cancelled) return () => {};
        subscribers.add(cb);
        return () => { subscribers.delete(cb); };
      },
      navigate(path) { if (!cancelled) navRef.current(resolveNavigate(routerBase, path)); },
      theme: 'dark',
      onUnauthorized() { if (!cancelled) void logoutRef.current(); },
    };
    import(/* @vite-ignore */ entry.bundle)
      .then(async mod => {
        if (cancelled) return;
        const checked = checkPageModule(mod, entry.module);
        if (!checked.ok) { setError(checked.error); return; }
        let returned: unknown;
        try {
          returned = await checked.mount(el, ctx);
        } catch (e) {
          el.replaceChildren();
          if (!cancelled) setError(`module ${entry.module}: the page failed to mount (${messageOf(e)})`);
          return;
        }
        const u = checkUnmount(returned, entry.module);
        if (!u.ok) {
          el.replaceChildren();
          if (!cancelled) setError(u.error);
          return;
        }
        if (cancelled) {
          try { u.unmount(); } catch (e) { console.error(e); }
          el.remove();
          return;
        }
        unmount = u.unmount;
      })
      .catch(e => {
        if (!cancelled) setError(`module ${entry.module}: the page failed to load (${messageOf(e)})`);
      });
    return () => {
      cancelled = true;
      subscribers.clear();
      if (unmount) { try { unmount(); } catch (e) { console.error(e); } }
      el.remove();
    };
    // Re-mount only when the page itself changes; subpath changes go through onLocationChange.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entry?.bundle, entry?.module, entry?.id]);

  // The host container is always rendered, so the mount effect finds it whatever the state.
  return (
    <div className="qod-page">
      {loading && <div className="loading">Loading pages...</div>}
      {!loading && !entry && <Navigate to={landing} replace />}
      {error && <div className="card"><p style={{ color: 'var(--bad)' }}>{error}</p></div>}
      <div ref={host} />
    </div>
  );
}

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
