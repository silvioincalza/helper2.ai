import type { HTMLAttributes, Ref } from 'react';

/**
 * A heading at the level that fits where it sits: the same part is an h2 on the resource page and an h3 in the
 * results preview.
 */
export function Heading({ level, ...props }: { level: number; ref?: Ref<HTMLHeadingElement> } & HTMLAttributes<HTMLHeadingElement>) {
  const Tag = `h${Math.min(Math.max(level, 1), 6)}` as 'h2';
  return <Tag {...props} />;
}
