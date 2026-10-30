import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { clearApiCache } from '@/hooks/useApi';
import i18n from '@/i18n';

beforeEach(async () => {
  window.localStorage.clear();
  clearApiCache();
  // jsdom has no layout: <ScrollRestoration/> scrolls through this spy.
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  await i18n.changeLanguage('en');
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
