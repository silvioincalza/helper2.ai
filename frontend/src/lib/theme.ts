export type Theme = 'light' | 'dark';
export const THEME_KEY = 'h2.theme';

/** The other theme: one click on the theme button switches light ↔ dark. */
export function otherTheme(theme: Theme): Theme {
  return theme === 'dark' ? 'light' : 'dark';
}

/**
 * The theme on the page: data-theme on <html> is the one source of truth. public/theme.js sets it from the saved
 * choice before first paint and chooseTheme updates it, so the button and the page always agree, even when
 * storage can be read but not written.
 */
export function currentTheme(): Theme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

const listeners = new Set<() => void>();

/** Calls back whenever the theme changes. For useSyncExternalStore. */
export function subscribeTheme(onChange: () => void): () => void {
  listeners.add(onChange);
  return () => {
    listeners.delete(onChange);
  };
}

/** Applies a theme and remembers it where storage allows. */
export function chooseTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  try {
    window.localStorage.setItem(THEME_KEY, theme);
  } catch {
    /* storage unavailable: the choice lasts for this page only */
  }
  for (const l of listeners) l();
}
