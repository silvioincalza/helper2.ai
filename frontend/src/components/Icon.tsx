const PATHS: Record<string, string> = {
  check: 'M4 12l5 5L20 6',
  cross: 'M6 6l12 12M18 6L6 18',
  clock: 'M12 7v5l3 2M12 3a9 9 0 100 18 9 9 0 000-18z',
  pause: 'M9 6v12M15 6v12',
  minus: 'M6 12h12',
  info: 'M12 8h.01M11 12h1v5h1M12 3a9 9 0 100 18 9 9 0 000-18z',
  warn: 'M12 4l9 16H3L12 4zM12 10v4M12 17h.01',
  question: 'M9.5 9a2.5 2.5 0 115 0c0 1.5-2.5 2-2.5 3.5M12 17h.01',
  bookmark: 'M7 4h10v16l-5-4-5 4V4z',
  link: 'M14 5h5v5M19 5l-8 8M18 14v5H5V6h5',
  refresh: 'M20 5v5h-5M4 19v-5h5M5.6 9A7 7 0 0118 7l2 3M18.4 15A7 7 0 016 17l-2-3',
  filter: 'M4 5h16l-6 8v6l-4-2v-4L4 5z',
  sparkle: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3zM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15z',
};

/** Decorative icon; meaning is always carried by adjacent text. */
export function Icon({ name }: { name: keyof typeof PATHS | string }) {
  return (
    <svg className="icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d={PATHS[name] ?? ''} />
    </svg>
  );
}
