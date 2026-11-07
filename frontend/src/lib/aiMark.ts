/**
 * Machine-readable marking of AI-written text in the page (EU AI Act Art. 50(2), ADR 0018).
 * Spread on the element that holds only AI-written text. The visible "AI" labels stay as they are.
 */
export function aiMark(model?: string | null): {
  'data-ai-generated': 'true';
  'data-ai-model'?: string;
} {
  return model
    ? { 'data-ai-generated': 'true', 'data-ai-model': model }
    : { 'data-ai-generated': 'true' };
}

/**
 * Value origins written by a model (ValueOrigin in the API contract). A self-declared value is the vendor's claim, but
 * a model picked it from the page; an AI-estimated value is the model's own judgement (ADR 0023).
 */
export function isAiOrigin(origin: string): boolean {
  return (
    origin === 'AI_EXTRACTED' ||
    origin === 'AI_SUMMARY' ||
    origin === 'AI_ESTIMATED' ||
    origin === 'SELF_DECLARED'
  );
}
