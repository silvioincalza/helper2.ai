import type { AiContentMarker, ValueOrigin } from '@/api/types';

export const AI_ORIGIN = {
  read: 'AI_EXTRACTED',
  declared: 'SELF_DECLARED',
  estimated: 'AI_ESTIMATED',
  written: 'AI_SUMMARY',
} as const satisfies Record<string, ValueOrigin>;

export type AiOrigin = (typeof AI_ORIGIN)[keyof typeof AI_ORIGIN];

const AI_ORIGINS: readonly ValueOrigin[] = Object.values(AI_ORIGIN);

export function isAiOrigin(origin: ValueOrigin): origin is AiOrigin {
  return AI_ORIGINS.includes(origin);
}

type AiModelAttribute = { 'data-ai-model'?: string };
type AiMarkAttributes = { 'data-ai-generated': 'true' } & AiModelAttribute;

export function aiModel(model?: string | null): AiModelAttribute {
  return model ? { 'data-ai-model': model } : {};
}

export function aiMark(model?: string | null): AiMarkAttributes {
  return { 'data-ai-generated': 'true', ...aiModel(model) };
}

export function aiMarkWhen(
  condition: boolean,
  model?: string | null,
): AiMarkAttributes | Record<string, never> {
  return condition ? aiMark(model) : {};
}

type AiSourceAttributes = { 'data-ai-provider'?: string; 'data-ai-models'?: string };

export function aiSource(marker: AiContentMarker | null | undefined): AiSourceAttributes {
  if (!marker) return {};
  const provider = { 'data-ai-provider': marker.provider };
  return marker.models.length > 0
    ? { ...provider, 'data-ai-models': marker.models.join(' ') }
    : provider;
}

export function marksFieldsOf(marker: AiContentMarker | null | undefined, part: string): boolean {
  return marker?.fields.some((field) => field.startsWith(part)) ?? false;
}
