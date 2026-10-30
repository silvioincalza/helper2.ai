/**
 * Where [quote] sits inside [text], matched like the validator does (case and runs of whitespace ignored).
 * Null when it is not there (e.g. the quote came from the title), so the caller shows the quote alone.
 */
export function locateQuote(text: string, quote: string): { before: string; match: string; after: string } | null {
  const words = quote.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return null;
  const pattern = new RegExp(words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s+'), 'i');
  const m = pattern.exec(text);
  if (!m) return null;
  return { before: text.slice(0, m.index), match: m[0], after: text.slice(m.index + m[0].length) };
}
