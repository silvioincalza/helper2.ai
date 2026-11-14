import type { SourceInfo } from '@/api/types';

export function newThisWeek(source: SourceInfo): number | null {
  return source.newThisWeek ?? null;
}

export function radarSources(sources: SourceInfo[]): SourceInfo[] {
  return sources.filter((s) => s.enabled && s.external);
}

export function listTotal(response: { total: number } | undefined, itemsShown: number): number {
  return Math.max(response?.total ?? 0, itemsShown);
}
