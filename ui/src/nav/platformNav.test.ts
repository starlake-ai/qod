import { describe, expect, it } from 'vitest';
import { layoutFor, platformNavItems } from './platformNav';

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
