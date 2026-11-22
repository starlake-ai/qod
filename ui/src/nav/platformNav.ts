import type { ConsolePageView } from '../api/types';
import type { NavItem } from './navModel';

export type Layout = 'admin' | 'staff' | 'profile';

/** The console layout a whoami role gets (issue #274). */
export function layoutFor(role: string | null): Layout {
  const r = (role ?? '').toLowerCase();
  if (r === 'admin') return 'admin';
  if (r === 'staff') return 'staff';
  return 'profile';
}

/** Router path of a page (the BrowserRouter basename is /ui). */
export function routerPath(page: ConsolePageView): string {
  return page.path.replace(/^\/ui(?=\/)/, '');
}

/** The Platform nav section: one group, one child per module page the caller may see. */
export function platformNavItems(pages: ConsolePageView[]): NavItem[] {
  if (pages.length === 0) return [];
  return [{
    id: 'platform',
    label: 'Platform',
    icon: 'config',
    children: pages.map(p => ({ id: `x-${p.module}-${p.id}`, label: p.label, icon: 'config', path: routerPath(p) })),
  }];
}
