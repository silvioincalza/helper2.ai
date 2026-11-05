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
]);

/**
 * A product gets it only when you install or run it yourself: self-hosted, a desktop or mobile app, a command-line
 * tool, a browser or VS Code extension.
 */
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
