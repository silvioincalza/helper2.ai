export type Theme = 'light' | 'dark';
/** What the visitor chose: a fixed theme, or follow the device setting (nothing saved). */
export type ThemeChoice = Theme | 'system';
export const THEME_KEY = 'h2.theme';

const ORDER: readonly ThemeChoice[] = ['system', 'light', 'dark'];

/** The choice one click on the theme button moves to: system → light → dark → system. */
export function nextChoice(choice: ThemeChoice): ThemeChoice {
  return ORDER[(ORDER.indexOf(choice) + 1) % ORDER.length] ?? 'system';
}

/** Where storage is blocked, the choice lasts for this page only. */
let unsaved: ThemeChoice = 'system';

/** The saved choice (public/theme.js applies it before first paint); no saved choice means "follow the device". */
export function currentChoice(): ThemeChoice {
  try {
    const saved = window.localStorage.getItem(THEME_KEY);
    return saved === 'light' || saved === 'dark' ? saved : 'system';
  } catch {
    return unsaved;
  }
}

const listeners = new Set<() => void>();

/** Calls back whenever the choice changes. For useSyncExternalStore. */
export function subscribeTheme(onChange: () => void): () => void {
  listeners.add(onChange);
  return () => {
    listeners.delete(onChange);
  };
}

/**
 * Applies a choice and remembers it. "system" forgets the saved theme, so public/theme.js follows the device
 * again, live.
 */
export function chooseTheme(choice: ThemeChoice): void {
  unsaved = choice;
  try {
    if (choice === 'system') window.localStorage.removeItem(THEME_KEY);
    else window.localStorage.setItem(THEME_KEY, choice);
  } catch {
    /* storage unavailable: `unsaved` keeps the choice for this page */
  }
  const device: Theme = window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  document.documentElement.dataset.theme = choice === 'system' ? device : choice;
  for (const l of listeners) l();
}
