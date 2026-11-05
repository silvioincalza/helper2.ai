/**
 * Tells search engines how to treat this page (SEO review F3, F8). React 19 moves the tag into <head>. `noindex` for
 * pages that do not exist; `noindex, follow` for search results and personal pages: not indexed, but the result links
 * on them are still followed.
 */
export function Robots({ content }: { content: 'noindex' | 'noindex, follow' }) {
  return <meta name="robots" content={content} />;
}
