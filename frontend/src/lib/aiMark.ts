/**
 * Machine-readable marking of AI-written text in the page (EU AI Act Art. 50(2), ADR 0038).
 * Spread on the element that holds only AI-written text. The visible "AI" labels stay as they are.
 */
export function aiMark(model?: string | null): { 'data-ai-generated': 'true'; 'data-ai-model'?: string } {
  return model ? { 'data-ai-generated': 'true', 'data-ai-model': model } : { 'data-ai-generated': 'true' };
}

/** Value origins written by a model (ValueOrigin in the API contract). */
export function isAiOrigin(origin: string): boolean {
  return origin === 'AI_EXTRACTED' || origin === 'AI_SUMMARY';
}
