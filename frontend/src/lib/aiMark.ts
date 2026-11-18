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

type AiProviderAttribute = { 'data-ai-provider'?: string };
type AiSourceAttributes = AiProviderAttribute & { 'data-ai-models'?: string };

export function aiProvider(marker: AiContentMarker | null | undefined): AiProviderAttribute {
  return marker ? { 'data-ai-provider': marker.provider } : {};
}

export function aiSource(marker: AiContentMarker | null | undefined): AiSourceAttributes {
  if (!marker) return {};
  return marker.models.length > 0 ? aiSourceWithModels(marker) : aiProvider(marker);
}

export function aiSourceWithModels(marker: AiContentMarker | null | undefined): AiSourceAttributes {
  if (!marker) return {};
  return { ...aiProvider(marker), 'data-ai-models': marker.models.join(' ') };
}

export function marksFieldsOf(marker: AiContentMarker | null | undefined, part: string): boolean {
  return marker?.fields.some((field) => field.startsWith(part)) ?? false;
}
