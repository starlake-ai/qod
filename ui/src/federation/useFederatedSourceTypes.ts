import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { FederatedSourceTypeView } from '../api/types';

/** The module-provided source types; [] on any failure, so the form offers the built-in four. */
export function useFederatedSourceTypes(): FederatedSourceTypeView[] {
  const [types, setTypes] = useState<FederatedSourceTypeView[]>([]);
  useEffect(() => {
    let live = true;
    api.federatedSourceTypes()
      .then(r => { if (live) setTypes(r.types); })
      .catch(() => { if (live) setTypes([]); });
    return () => { live = false; };
  }, []);
  return types;
}
