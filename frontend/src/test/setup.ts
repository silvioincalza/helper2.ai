import '@testing-library/jest-dom/vitest';
import { act, cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { clearApiCache } from '@/hooks/useApi';
import i18n from '@/i18n';

// jsdom has no scrolling; ScrollRestoration calls it on every navigation.
window.scrollTo = () => undefined;

// A state update outside act() means a test checked the screen before React finished: fail it, do not just warn.
const realError = console.error;
let actWarnings: string[] = [];

beforeEach(async () => {
  actWarnings = [];
  console.error = (...args: unknown[]) => {
    const message = args.map(String).join(' ');
    if (message.includes('not wrapped in act')) actWarnings.push(args.slice(1).map(String).join(' ') || message);
    realError(...args);
  };
  window.localStorage.clear();
  await i18n.changeLanguage('en');
});

afterEach(async () => {
  // Answers a test did not wait for (the sources list, /me) arrive inside act() before the screen is torn down.
  if (!vi.isFakeTimers()) await act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });
  cleanup();
  clearApiCache();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  console.error = realError;
  if (actWarnings.length > 0) throw new Error(`State updated outside act() in: ${actWarnings.join(', ')}`);
});
