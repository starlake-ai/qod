import { describe, expect, it } from 'vitest';
import type { FederatedSourceTypeView } from '../api/types';
import {
  BUILT_IN_SOURCE_TYPES, editorOwnsActions, formKindOf, isBuiltInSourceType, NO_EDITOR_MESSAGE,
  sourceTypeOptions,
} from './sourceTypes';

const plugins: FederatedSourceTypeView[] = [
  { module: 'test-module', sourceType: 'test_connector', label: 'Test connector', editorBundle: '/test-module/editor.js', editsHeldSources: true },
  { module: 'test-module', sourceType: 'plain_connector', label: 'plain_connector', editorBundle: null, editsHeldSources: false },
];

/** The picker as the form offered it before module types existed, copied from FederationSection. */
const builtIns = [
  { value: 'sql', label: 'sql - your own ATTACH / setup SQL' },
  { value: 'iceberg_rest', label: 'iceberg_rest - external Iceberg REST catalog' },
  { value: 'postgres', label: 'postgres - external Postgres database' },
  { value: 'mysql', label: 'mysql - external MySQL database' },
];

describe('the type picker', () => {
  it('offers the four built-in types unchanged when no module adds one', () => {
    expect(BUILT_IN_SOURCE_TYPES).toEqual(builtIns);
    expect(sourceTypeOptions([], 'sql')).toEqual(builtIns);
  });

  it('lists the module types after the built-in four as <sourceType>: <label>', () => {
    expect(sourceTypeOptions(plugins, 'sql')).toEqual([
      ...builtIns,
      { value: 'test_connector', label: 'test_connector: Test connector' },
      { value: 'plain_connector', label: 'plain_connector: plain_connector' },
    ]);
  });

  it('keeps the type of a stored source that no module lists', () => {
    expect(sourceTypeOptions([], 'unloaded_connector'))
      .toEqual([...builtIns, { value: 'unloaded_connector', label: 'unloaded_connector' }]);
  });
});

describe('editorOwnsActions', () => {
  it('leaves the actions to a mounted module editor', () => {
    expect(editorOwnsActions('plugin-editor', false)).toBe(true);
    expect(editorOwnsActions('plugin-editor', true)).toBe(true);
  });

  it('keeps the form actions while the editor loads, without an editor, and for built-in types', () => {
    expect(editorOwnsActions('plugin-editor', null)).toBe(false);
    expect(editorOwnsActions('plugin-no-editor', false)).toBe(false);
    for (const kind of ['sql', 'iceberg', 'rdbms'] as const) expect(editorOwnsActions(kind, false)).toBe(false);
  });
});

describe('formKindOf', () => {
  it('keeps the form of each built-in type', () => {
    expect(formKindOf('sql', plugins)).toBe('sql');
    expect(formKindOf('iceberg_rest', plugins)).toBe('iceberg');
    expect(formKindOf('postgres', plugins)).toBe('rdbms');
    expect(formKindOf('mysql', plugins)).toBe('rdbms');
  });

  it('mounts the editor of a module type that names one', () => {
    expect(formKindOf('test_connector', plugins)).toBe('plugin-editor');
  });

  it('shows the no-editor message for any other type', () => {
    expect(formKindOf('plain_connector', plugins)).toBe('plugin-no-editor');
    expect(formKindOf('unloaded_connector', plugins)).toBe('plugin-no-editor');
    expect(formKindOf('test_connector', [])).toBe('plugin-no-editor');
    expect(NO_EDITOR_MESSAGE).toBe('this source type has no editor; use the REST API');
  });

  it('tells a built-in type from a module type', () => {
    for (const b of builtIns) expect(isBuiltInSourceType(b.value)).toBe(true);
    expect(isBuiltInSourceType('test_connector')).toBe(false);
  });
});
