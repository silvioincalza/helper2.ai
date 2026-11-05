import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router';

/** Marks every tag the server writes into the shell for crawlers (ADR 0050). */
const SERVER_HEAD = '[data-h2-head]';

/**
 * Drops the server's head tags at the first move to another page: they describe the page the visitor landed on
 * (title, description, canonical, robots, JSON-LD), not the next one. Crawlers read the first load; from then on the
 * app keeps the title itself.
 */
export function useDropServerHead() {
  const { pathname } = useLocation();
  const landed = useRef(pathname);
  useEffect(() => {
    if (pathname === landed.current) return;
    const title = document.title;
    document.querySelectorAll(SERVER_HEAD).forEach((el) => el.remove());
    // Keep the tab's title until the new page sets its own.
    if (!document.title) document.title = title;
  }, [pathname]);
}
