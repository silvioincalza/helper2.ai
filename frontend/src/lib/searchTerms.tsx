import type { ReactNode } from 'react';

/** Words shorter than this are not marked: "a", "of", "to" would light up every line. */
const MIN_TERM = 3;

/** Common English words that say nothing about a match. */
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

/** The distinct words of the search, lower case, longest first so "vectordb" wins over "vector". */
export function searchTerms(query: string): string[] {
  const words = query
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length >= MIN_TERM && !STOP.has(w));
  return [...new Set(words)].sort((a, b) => b.length - a.length);
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * The text with each search word in bold (`<b class="h2-hit">`), so a row shows at a glance why it matched. Matching
 * ignores case and finds a word inside a longer one ("vector" in "vector_database"). Only the source's own text is
 * marked, never AI-written text: the bold is ours, not the source's emphasis.
 */
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
