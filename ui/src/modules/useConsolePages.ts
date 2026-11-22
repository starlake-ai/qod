import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { ConsolePageView } from '../api/types';

/** The module pages the signed-in caller may see; [] on any failure. */
export function useConsolePages(): { pages: ConsolePageView[]; loading: boolean } {
  const [pages, setPages] = useState<ConsolePageView[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let live = true;
    api.consolePages()
      .then(r => { if (live) setPages(r.pages); })
      .catch(() => { if (live) setPages([]); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, []);
  return { pages, loading };
}
