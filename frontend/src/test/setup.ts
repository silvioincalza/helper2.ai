import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { clearApiCache } from '@/hooks/useApi';
import i18n from '@/i18n';

// jsdom has no scrolling; ScrollRestoration calls it on every navigation.
window.scrollTo = () => undefined;

beforeEach(async () => {
  window.localStorage.clear();
  await i18n.changeLanguage('en');
});

afterEach(() => {
  cleanup();
  clearApiCache();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
