const MAX = 60;

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
