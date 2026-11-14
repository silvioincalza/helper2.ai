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
