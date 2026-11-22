import { useEffect, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { ConsolePageView } from '../api/types';
import {
  checkPageModule, findPage, resolveNavigate, subpathOf, type PageContext, type Unmount,
} from './pageContract';

/** Renders a module's console page (contract 1) inside the console layout. A page the caller
 *  cannot see redirects to `landing`; a page that fails to load, speaks another contract or
 *  throws in mount shows an error panel naming its module, and the rest of the console works on. */
export default function ModulePage({ pages, landing, loading = false }: {
  pages: ConsolePageView[];
  landing: string;
  loading?: boolean;
}) {
  const { module, page } = useParams();
  const entry = findPage(pages, module, page);
  const host = useRef<HTMLDivElement>(null);
  const listeners = useRef(new Set<(s: string) => void>());
  const [error, setError] = useState<string | null>(null);
  const location = useLocation();
  const nav = useNavigate();
  const { username, superuser, permissions, logout } = useAuth();
  // The page keeps its context for its whole life; these refs let it reach the current router
  // and session functions.
  const navRef = useRef(nav);
  navRef.current = nav;
  const logoutRef = useRef(logout);
  logoutRef.current = logout;
  const routerBase = `/x/${module}/${page}`;
  const subpath = subpathOf(location.pathname, routerBase);

  useEffect(() => {
    listeners.current.forEach(cb => {
      try { cb(subpath); } catch (e) { console.error(e); }
    });
  }, [subpath]);

  useEffect(() => {
    if (!entry || !host.current) return;
    const el = host.current;
    const subscribers = listeners.current;
    let unmount: Unmount | null = null;
    let cancelled = false;
    setError(null);
    const ctx: PageContext = {
      contract: 1,
      apiBase: '/api',
      basePath: entry.path,
      user: { username: username ?? '', superuser, permissions },
      subpath,
      onLocationChange(cb) {
        subscribers.add(cb);
        return () => { subscribers.delete(cb); };
      },
      navigate(path) { navRef.current(resolveNavigate(routerBase, path)); },
      theme: 'dark',
      onUnauthorized() { void logoutRef.current(); },
    };
    import(/* @vite-ignore */ entry.bundle)
      .then(async mod => {
        if (cancelled) return;
        const checked = checkPageModule(mod, entry.module);
        if (!checked.ok) { setError(checked.error); return; }
        let u: Unmount;
        try {
          u = await checked.mount(el, ctx);
        } catch (e) {
          if (!cancelled) setError(`module ${entry.module}: the page failed to mount (${messageOf(e)})`);
          return;
        }
        if (cancelled) { try { u(); } catch (e) { console.error(e); } return; }
        unmount = u;
      })
      .catch(e => {
        if (!cancelled) setError(`module ${entry.module}: the page failed to load (${messageOf(e)})`);
      });
    return () => {
      cancelled = true;
      subscribers.clear();
      if (unmount) { try { unmount(); } catch (e) { console.error(e); } }
      el.replaceChildren();
    };
    // Re-mount only when the page itself changes; subpath changes go through onLocationChange.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entry?.bundle, entry?.module, entry?.id]);

  if (loading) return <div className="loading">Loading pages...</div>;
  if (!entry) return <Navigate to={landing} replace />;
  return (
    <div className="qod-page">
      {error && <div className="card"><p style={{ color: 'var(--bad)' }}>{error}</p></div>}
      <div ref={host} />
    </div>
  );
}

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
