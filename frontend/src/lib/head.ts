import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router';

const SERVER_HEAD = '[data-h2-head]';

export function useDropServerHead() {
  const { pathname } = useLocation();
  const landed = useRef(pathname);
  useEffect(() => {
    if (pathname === landed.current) return;
    const title = document.title;
    document.querySelectorAll(SERVER_HEAD).forEach((el) => el.remove());
    if (!document.title) document.title = title;
  }, [pathname]);
}
