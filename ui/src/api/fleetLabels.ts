/** A fleet server's labels as [key, value] pairs sorted by key, so every row
  * reads in the same order whatever order the JSON object came in. Plain
  * code-unit order, as the manager's FleetLabels.render sorts them. */
export function sortedLabels(labels: Record<string, string> | undefined): [string, string][] {
  return Object.entries(labels ?? {}).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
}
