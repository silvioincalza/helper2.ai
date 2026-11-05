import type { ResultCard } from '@/api/types';

/**
 * Things a user installs or runs, so the setup guide has something to show: they get "Set it up". Papers, datasets,
 * prompts, articles, courses, events, news and other types get none.
 */
const GUIDED: ReadonlySet<ResultCard['type']> = new Set([
  'MCP_SERVER',
  'MODEL',
  'LIBRARY',
  'AGENT',
  'SKILL',
  'PRODUCT',
]);

export function canGuide(card: ResultCard): boolean {
  return GUIDED.has(card.type);
}
