import type { ResourceType } from '@/api/types';

const TAG: Partial<Record<ResourceType, string>> = {
  PAPER: 'tag-paper', MODEL: 'tag-model', NEWS: 'tag-news', ARTICLE: 'tag-news', MCP_SERVER: 'tag-mcp',
};

/** Class names for a resource type's tag, per the design system's type-tag colours (plain tint for the rest). */
export function typeTagClass(type: ResourceType): string {
  return `type-tag ${TAG[type] ?? ''}`.trim();
}
