import type { ReactNode } from 'react';

const MIN_TERM = 3;

const STOP = new Set([
  'and',
  'are',
  'for',
  'from',
  'how',
  'the',
  'that',
  'this',
  'what',
  'with',
  'you',
  'your',
]);

export function searchTerms(query: string): string[] {
  const words = query
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length >= MIN_TERM && !STOP.has(w));
  return [...new Set(words)].sort((a, b) => b.length - a.length);
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export function withSearchTerms(text: string, terms: readonly string[]): ReactNode {
  if (!text || terms.length === 0) return text;
  const pattern = new RegExp(`(${terms.map(escape).join('|')})`, 'giu');
  const parts = text.split(pattern);
  if (parts.length === 1) return text;
  return parts.map((part, i) =>
    i % 2 === 1 ? (
      <b key={i} className="h2-hit">
        {part}
      </b>
    ) : (
      part
    ),
  );
}
