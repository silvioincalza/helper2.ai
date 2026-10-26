import type { ResultCard } from '@/api/types';

/** Things the guide can pick for a need. Papers, datasets, prompts and events get no "Guide me". */
const GUIDED: ReadonlySet<ResultCard['type']> = new Set(['MCP_SERVER', 'MODEL', 'LIBRARY', 'AGENT', 'SKILL', 'PRODUCT']);

export function canGuide(card: ResultCard): boolean {
  return GUIDED.has(card.type);
}
