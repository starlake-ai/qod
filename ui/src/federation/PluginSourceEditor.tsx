import { useEffect, useRef, useState } from 'react';
import type { FederatedSourceResponse } from '../api/types';
import {
  sourceEditorContext, type SaveResult, type SourceEditorContext, type SourceEditorSecret,
} from './sourceEditorContract';
import { startSourceEditor } from './sourceEditorHost';

/** What forces a fresh editor mount: a change in any of these remounts it. The source type is
 *  part of it because two types of one module share a bundle and, in create mode, have no source
 *  id, so without it the editor would keep the previous type's state and save it under the new one. */
export function editorMountDeps(p: {
  sourceType: string; bundle: string; module: string; tenant: string; tenantDb: string;
  source: { id: string } | null; readOnly: boolean;
}): unknown[] {
  return [p.sourceType, p.bundle, p.module, p.tenant, p.tenantDb, p.source?.id, p.readOnly];
}

/** Mounts a module's source editor (contract 1) in the federation form, in place of the type's
 *  fields. It remounts only when the source type, the bundle, the module, the tenant-db, the source or the read-only
 *  state changes; `save` and `cancel` always reach the form's current handlers, so the alias and
 *  description typed after the mount are the ones saved. An editor that fails shows an error
 *  naming its module, and the rest of the form works on. */
export default function PluginSourceEditor(props: {
  sourceType: string;
  module: string;
  bundle: string;
  tenant: string;
  tenantDb: string;
  user: SourceEditorContext['user'];
  source: FederatedSourceResponse | null;
  readOnly: boolean;
  save: (pluginConfig: unknown, secrets: SourceEditorSecret[]) => Promise<SaveResult>;
  cancel: () => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  const saveRef = useRef(props.save);
  saveRef.current = props.save;
  const cancelRef = useRef(props.cancel);
  cancelRef.current = props.cancel;

  useEffect(() => {
    const container = host.current;
    if (!container) return;
    // Each run mounts into an element of its own (as ModulePage does).
    const el = document.createElement('div');
    container.appendChild(el);
    setError(null);
    const stop = startSourceEditor({
      load: () => import(/* @vite-ignore */ props.bundle),
      el,
      module: props.module,
      ctx: sourceEditorContext({
        tenant: props.tenant,
        tenantDb: props.tenantDb,
        user: props.user,
        source: props.source,
        readOnly: props.readOnly,
        save: (pluginConfig, secrets) => saveRef.current(pluginConfig, secrets),
        cancel: () => cancelRef.current(),
      }),
      onError: setError,
    });
    return () => {
      stop();
      el.remove();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, editorMountDeps(props));

  // The host container is always rendered, so the mount effect finds it whatever the state.
  return (
    <div className="qod-source-editor">
      {error && <div className="login-err">{error}</div>}
      <div ref={host} />
    </div>
  );
}
