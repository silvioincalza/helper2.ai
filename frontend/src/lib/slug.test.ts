import { describe, expect, it } from 'vitest';
import { slugOf } from './slug';

describe('slugOf', () => {
  const vectors: [string, string][] = [
    ['postgres-mcp', 'postgres-mcp'],
    ['LangChain: Build LLM apps', 'langchain-build-llm-apps'],
    ['Café Crème 2.0', 'cafe-creme-2-0'],
    ['  --Hello__World--  ', 'hello-world'],
    ['日本語', ''],
    ['', ''],
    ['word '.repeat(20).trim(), 'word-word-word-word-word-word-word-word-word-word-word-word'],
    ['a'.repeat(70), 'a'.repeat(60)],
  ];
  it.each(vectors)('%s → %s', (title, slug) => {
    expect(slugOf(title)).toBe(slug);
  });
});
