import type { FederatedSourceTypeView } from '../api/types';

export interface SourceTypeOption { value: string; label: string }

/** The four built-in types, in the picker's order and words, as the form has always offered them. */
export const BUILT_IN_SOURCE_TYPES: readonly SourceTypeOption[] = [
  { value: 'sql', label: 'sql - your own ATTACH / setup SQL' },
  { value: 'iceberg_rest', label: 'iceberg_rest - external Iceberg REST catalog' },
  { value: 'postgres', label: 'postgres - external Postgres database' },
  { value: 'mysql', label: 'mysql - external MySQL database' },
];

export const NO_EDITOR_MESSAGE = 'this source type has no editor; use the REST API';

export function isBuiltInSourceType(t: string): boolean {
  return BUILT_IN_SOURCE_TYPES.some(o => o.value === t);
}

/** The built-in four, then each module type as `<sourceType>: <label>`. A stored source whose type
 *  no module lists keeps its type as the last option, so the locked picker shows what it is. */
export function sourceTypeOptions(plugins: FederatedSourceTypeView[], current: string): SourceTypeOption[] {
  const options = [
    ...BUILT_IN_SOURCE_TYPES,
    ...plugins.map(p => ({ value: p.sourceType, label: `${p.sourceType}: ${p.label}` })),
  ];
  return options.some(o => o.value === current) ? options : [...options, { value: current, label: current }];
}

/** Which fields the form shows for a type. */
export type FormKind = 'sql' | 'iceberg' | 'rdbms' | 'plugin-editor' | 'plugin-no-editor';

export function formKindOf(sourceType: string, plugins: FederatedSourceTypeView[]): FormKind {
  switch (sourceType) {
    case 'sql': return 'sql';
    case 'iceberg_rest': return 'iceberg';
    case 'postgres':
    case 'mysql': return 'rdbms';
  }
  return plugins.find(p => p.sourceType === sourceType)?.editorBundle ? 'plugin-editor' : 'plugin-no-editor';
}
