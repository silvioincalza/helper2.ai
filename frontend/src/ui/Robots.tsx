export function Robots({ content }: { content: 'noindex' | 'noindex, follow' }) {
  return <meta name="robots" content={content} />;
}
