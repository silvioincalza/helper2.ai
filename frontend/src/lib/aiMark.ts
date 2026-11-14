import type { AiContentMarker } from '@/api/types';

export function aiMark(model?: string | null): {
  'data-ai-generated': 'true';
  'data-ai-model'?: string;
} {
  return model
    ? { 'data-ai-generated': 'true', 'data-ai-model': model }
    : { 'data-ai-generated': 'true' };
}

export function isAiOrigin(origin: string): boolean {
  return (
    origin === 'AI_EXTRACTED' ||
    origin === 'AI_SUMMARY' ||
    origin === 'AI_ESTIMATED' ||
    origin === 'SELF_DECLARED'
  );
}

export function aiSource(marker: AiContentMarker | null | undefined): {
  'data-ai-provider'?: string;
  'data-ai-models'?: string;
} {
  if (!marker) return {};
  return marker.models.length > 0
    ? { 'data-ai-provider': marker.provider, 'data-ai-models': marker.models.join(' ') }
    : { 'data-ai-provider': marker.provider };
}

export function marksFieldsOf(marker: AiContentMarker | null | undefined, part: string): boolean {
  return marker?.fields.some((field) => field.startsWith(part)) ?? false;
}
