import { describe, expect, it } from 'vitest';
import { checkPageModule, findPage, resolveNavigate, subpathOf } from './pageContract';

describe('checkPageModule', () => {
  it('accepts contract 1 with a mount function', () => {
    const r = checkPageModule({ qodConsoleContract: 1, mount: () => () => {} }, 'hosted');
    expect(r.ok).toBe(true);
  });
  it('names the contract the page needs', () => {
    const r = checkPageModule({ qodConsoleContract: 2, mount: () => () => {} }, 'hosted');
    expect(r).toEqual({ ok: false, error: 'module hosted: this page needs console contract 2; this console supports 1' });
  });
  it('refuses a missing contract or mount', () => {
    expect(checkPageModule({ mount: () => () => {} }, 'm').ok).toBe(false);
    expect(checkPageModule({ qodConsoleContract: 1 }, 'm').ok).toBe(false);
    expect(checkPageModule(null, 'm').ok).toBe(false);
  });
});

describe('subpathOf', () => {
  it('is empty at the page root and keeps a leading slash below it', () => {
    expect(subpathOf('/x/hosted/quotas', '/x/hosted/quotas')).toBe('');
    expect(subpathOf('/x/hosted/quotas/', '/x/hosted/quotas')).toBe('');
    expect(subpathOf('/x/hosted/quotas/tenant/acme', '/x/hosted/quotas')).toBe('/tenant/acme');
  });
});

describe('resolveNavigate', () => {
  it('resolves a page-relative path under the page', () => {
    expect(resolveNavigate('/x/hosted/quotas', 'tenant/acme')).toBe('/x/hosted/quotas/tenant/acme');
    expect(resolveNavigate('/x/hosted/quotas', '/tenant/acme')).toBe('/x/hosted/quotas/tenant/acme');
    expect(resolveNavigate('/x/hosted/quotas', '')).toBe('/x/hosted/quotas');
  });
  it('passes an absolute console path through without the /ui basename', () => {
    expect(resolveNavigate('/x/hosted/quotas', '/ui/settings/profile')).toBe('/settings/profile');
    expect(resolveNavigate('/x/hosted/quotas', '/ui')).toBe('/');
  });
});

describe('findPage', () => {
  const pages = [
    { module: 'hosted', id: 'quotas', label: 'Quotas', path: '/ui/x/hosted/quotas', bundle: '/ops/q.js' },
    { module: 'billing', id: 'quotas', label: 'Billing quotas', path: '/ui/x/billing/quotas', bundle: '/ops/b.js' },
  ];
  it('matches on module and page id together', () => {
    expect(findPage(pages, 'billing', 'quotas')?.bundle).toBe('/ops/b.js');
  });
  it('finds nothing for a page the caller may not see', () => {
    expect(findPage(pages, 'hosted', 'billing')).toBeUndefined();
    expect(findPage(pages, undefined, 'quotas')).toBeUndefined();
    expect(findPage([], 'hosted', 'quotas')).toBeUndefined();
  });
});
