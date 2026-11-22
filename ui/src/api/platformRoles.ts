import type { PlatformPermissionView } from './types';

export const NOT_DECLARED = '(not declared)';

export function groupByModule(perms: PlatformPermissionView[]) {
  const by = new Map<string, PlatformPermissionView[]>();
  for (const p of perms) {
    const key = p.declared ? (p.module ?? NOT_DECLARED) : NOT_DECLARED;
    by.set(key, [...(by.get(key) ?? []), p]);
  }
  const keys = [...by.keys()].filter(k => k !== NOT_DECLARED).sort();
  if (by.has(NOT_DECLARED)) keys.push(NOT_DECLARED);
  return keys.map(module => ({
    module,
    permissions: by.get(module)!.sort((a, b) => a.name.localeCompare(b.name)),
  }));
}
