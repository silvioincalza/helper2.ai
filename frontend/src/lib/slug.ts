/** Longest slug, cut at a hyphen. The backend's `Slug.MAX` (ADR 0050). */
const MAX = 60;

/**
 * The readable part of a resource address: the title in plain lowercase words joined by hyphens, accents
 * dropped, at most 60 characters cut at a hyphen. The backend computes the same slug (`pages/ResourcePaths.kt`);
 * both tests share one table. Empty when the title has no Latin letter or digit.
 */
export function slugOf(title: string | null | undefined): string {
  const slug = (title ?? '')
    .normalize('NFKD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  if (slug.length <= MAX) return slug;
  const cut = slug.slice(0, MAX + 1);
  const end = cut.lastIndexOf('-');
  return (end > 0 ? cut.slice(0, end) : slug.slice(0, MAX)).replace(/^-+|-+$/g, '');
}
