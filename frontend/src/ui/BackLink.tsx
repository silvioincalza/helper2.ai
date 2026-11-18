import type { MouseEvent, ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';

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
  const onClick = (e: MouseEvent<HTMLAnchorElement>) => {
    const plain = e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey;
    if (!from || key === 'default' || !plain) return;
    e.preventDefault();
    void navigate(-1);
  };
  return (
    <Link to={from ?? fallback} onClick={onClick}>
      <span aria-hidden="true">← </span>
      {children}
    </Link>
  );
}
