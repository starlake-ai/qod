import { describe, expect, it } from 'vitest';
import { landingPath, layoutFor, platformNavItems, staffNavCtx } from './platformNav';
import { NAV, visibleNav } from './navModel';

describe('layoutFor', () => {
  it('maps whoami roles to layouts', () => {
    expect(layoutFor('admin')).toBe('admin');
    expect(layoutFor('ADMIN')).toBe('admin');
    expect(layoutFor('staff')).toBe('staff');
    expect(layoutFor('user')).toBe('profile');
    expect(layoutFor(null)).toBe('profile');
  });
});

describe('platformNavItems', () => {
  it('builds one Platform group with a child per page, in order', () => {
    const items = platformNavItems([
      { module: 'hosted', id: 'quotas', label: 'Quotas', path: '/ui/x/hosted/quotas', bundle: '/ops/q.js' },
      { module: 'hosted', id: 'billing', label: 'Billing', path: '/ui/x/hosted/billing', bundle: '/ops/b.js' },
    ]);
    expect(items).toHaveLength(1);
    expect(items[0].label).toBe('Platform');
    expect(items[0].children!.map(c => c.path)).toEqual(['/x/hosted/quotas', '/x/hosted/billing']);
  });
  it('is empty with no pages', () => {
    expect(platformNavItems([])).toEqual([]);
  });
});

describe('staffNavCtx', () => {
  it('leaves staff only the profile entry of the base nav', () => {
    const ids = visibleNav(NAV, staffNavCtx()).flatMap(i => [i.id, ...i.children.map(c => c.id)]);
    expect(ids).toEqual(['settings', 'profile']);
  });
});

describe('landingPath', () => {
  it('is the first page the caller may see', () => {
    expect(landingPath([
      { module: 'hosted', id: 'quotas', label: 'Quotas', path: '/ui/x/hosted/quotas', bundle: '/ops/q.js' },
      { module: 'hosted', id: 'billing', label: 'Billing', path: '/ui/x/hosted/billing', bundle: '/ops/b.js' },
    ])).toBe('/x/hosted/quotas');
  });
  it('is the profile with no pages', () => {
    expect(landingPath([])).toBe('/settings/profile');
  });
});
