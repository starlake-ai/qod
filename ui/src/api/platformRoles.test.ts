import { describe, expect, it } from 'vitest';
import { groupByModule } from './platformRoles';

describe('groupByModule', () => {
  it('groups declared permissions by module and puts undeclared ones last', () => {
    const g = groupByModule([
      { name: 'hosted.quota.extra', module: 'hosted', description: 'x', declared: true },
      { name: 'gone.x', declared: false },
      { name: 'billing.manage', module: 'billing', description: 'y', declared: true },
    ]);
    expect(g.map(x => x.module)).toEqual(['billing', 'hosted', '(not declared)']);
  });
});
