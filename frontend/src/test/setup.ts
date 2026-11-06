import '@testing-library/jest-dom/vitest';
import { act, cleanup, configure } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { clearApiCache } from '@/hooks/useApi';
import { clearPolicyCache } from '@/hooks/usePolicy';
import i18n from '@/i18n';

// A lazy page's first import can take over a second when the whole suite runs on a busy CI runner. The default wait
// of findBy and waitFor (1 s) then fails tests at random; 3 s does not. A real failure is reported 2 s later.
configure({ asyncUtilTimeout: 3000 });

// A state update outside act() means a test checked the screen before React finished: fail it, do not just warn.
const realError = console.error;
let actWarnings: string[] = [];

beforeEach(async () => {
  actWarnings = [];
  console.error = (...args: unknown[]) => {
    const message = args.map(String).join(' ');
    if (message.includes('not wrapped in act'))
      actWarnings.push(args.slice(1).map(String).join(' ') || message);
    realError(...args);
  };
  window.localStorage.clear();
  clearApiCache();
  clearPolicyCache();
  // jsdom has no layout: <ScrollRestoration/> scrolls through this spy.
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  // Nor scrollIntoView (the home page's jump to #news and #events): a stub a test can watch.
  Element.prototype.scrollIntoView = vi.fn();
  await i18n.changeLanguage('en');
});

afterEach(async () => {
  // Answers a test did not wait for (the sources list, /me) arrive inside act() before the screen is torn down.
  if (!vi.isFakeTimers())
    await act(async () => {
      for (let i = 0; i < 10; i++) await Promise.resolve();
    });
  cleanup();
  clearApiCache();
  clearPolicyCache();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  console.error = realError;
  if (actWarnings.length > 0)
    throw new Error(`State updated outside act() in: ${actWarnings.join(', ')}`);
});
