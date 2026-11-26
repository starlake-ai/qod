import { describe, expect, it } from 'vitest';
import { sortedLabels } from './fleetLabels';

describe('sortedLabels', () => {
  it('lists label pairs sorted by key, whatever the JSON order', () => {
    expect(sortedLabels({ zone: 'eu', disktype: 'ssd', 'example.com/tier': 'db' })).toEqual([
      ['disktype', 'ssd'],
      ['example.com/tier', 'db'],
      ['zone', 'eu'],
    ]);
  });

  it('lists nothing for no labels', () => {
    expect(sortedLabels({})).toEqual([]);
    expect(sortedLabels(undefined)).toEqual([]);
  });
});
