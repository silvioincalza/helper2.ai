import type { ResultCard } from '@/api/types';

const GUIDED: ReadonlySet<ResultCard['type']> = new Set([
  'MCP_SERVER',
  'MODEL',
  'LIBRARY',
  'AGENT',
  'SKILL',
]);

const INSTALLED_PLATFORMS: ReadonlySet<string> = new Set([
  'local',
  'desktop',
  'ios',
  'android',
  'cli',
  'browser-extension',
  'vscode',
]);

function isInstalledProduct(card: ResultCard): boolean {
  return (
    card.pricing === 'OPEN_SOURCE_SELF_HOSTED' ||
    card.platforms.some((p) => INSTALLED_PLATFORMS.has(p.toLowerCase()))
  );
}

export function canGuide(card: ResultCard): boolean {
  return GUIDED.has(card.type) || (card.type === 'PRODUCT' && isInstalledProduct(card));
}
