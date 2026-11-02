export type Theme = 'light' | 'dark';
export const THEME_KEY = 'h2.theme';

/** The other theme: one click on the theme button switches light ↔ dark. */
export function otherTheme(theme: Theme): Theme {
  return theme === 'dark' ? 'light' : 'dark';
}

/** Where storage is blocked, the choice lasts for this page only. */
let unsaved: Theme = 'light';

/** The saved theme (public/theme.js applies it before first paint); light unless dark was chosen. */
export function currentTheme(): Theme {
  try {
    return window.localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return unsaved;
  }
}

const listeners = new Set<() => void>();

/** Calls back whenever the theme changes. For useSyncExternalStore. */
export function subscribeTheme(onChange: () => void): () => void {
  listeners.add(onChange);
  return () => {
    listeners.delete(onChange);
  };
}

/** Applies a theme and remembers it. */
export function chooseTheme(theme: Theme): void {
  unsaved = theme;
  try {
    window.localStorage.setItem(THEME_KEY, theme);
  } catch {
    /* storage unavailable: `unsaved` keeps the choice for this page */
  }
  document.documentElement.dataset.theme = theme;
  for (const l of listeners) l();
}
