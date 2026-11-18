import type { MouseEvent, ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { isSameSitePath } from '@/lib/sameSitePath';

export function BackLink({
  from,
  fallback,
  children,
}: {
  from: string | undefined;
  fallback: string;
  children: ReactNode;
}) {
  const navigate = useNavigate();
  const { key } = useLocation();
  const list = from && isSameSitePath(from) ? from : undefined;
  const onClick = (e: MouseEvent<HTMLAnchorElement>) => {
    const plain = e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey;
    if (!list || key === 'default' || !plain) return;
    e.preventDefault();
    void navigate(-1);
  };
  return (
    <Link to={list ?? fallback} onClick={onClick}>
      <span aria-hidden="true">← </span>
      {children}
    </Link>
  );
}
