const CATALOG_SOURCES: ReadonlySet<string> = new Set(['searchavo-index', 'searchavo-semantic']);

export function rowSourceNames(
  sources: readonly { sourceId: string; sourceName: string }[],
  catalogName: string,
): string[] {
  return [
    ...new Set(sources.map((s) => (CATALOG_SOURCES.has(s.sourceId) ? catalogName : s.sourceName))),
  ];
}
