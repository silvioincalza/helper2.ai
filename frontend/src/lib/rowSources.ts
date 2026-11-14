const CATALOG_SOURCES: ReadonlySet<string> = new Set(['helper2-index', 'helper2-semantic']);

export function rowSourceNames(
  sources: readonly { sourceId: string; sourceName: string }[],
  catalogName: string,
): string[] {
  return [
    ...new Set(sources.map((s) => (CATALOG_SOURCES.has(s.sourceId) ? catalogName : s.sourceName))),
  ];
}
