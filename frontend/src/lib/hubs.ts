import type { ResourceType } from '@/api/types';

export interface Hub {
  path: string;
  type: ResourceType;
}

export const HUBS: readonly Hub[] = [
  { path: 'ai-apps', type: 'PRODUCT' },
  { path: 'ai-models', type: 'MODEL' },
  { path: 'ai-agents', type: 'AGENT' },
  { path: 'mcp-servers', type: 'MCP_SERVER' },
  { path: 'libraries', type: 'LIBRARY' },
  { path: 'agent-skills', type: 'SKILL' },
  { path: 'datasets', type: 'DATASET' },
  { path: 'papers', type: 'PAPER' },
  { path: 'prompts', type: 'PROMPT' },
  { path: 'courses', type: 'COURSE' },
];

export function hubOf(pathname: string): Hub | undefined {
  const path = pathname.replace(/^\/+|\/+$/g, '');
  return HUBS.find((h) => h.path === path);
}
