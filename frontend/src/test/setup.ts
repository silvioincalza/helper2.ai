import '@testing-library/jest-dom/vitest';
import { act, cleanup, configure } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { clearApiCache } from '@/hooks/useApi';
import { clearPolicyCache } from '@/hooks/usePolicy';
import i18n from '@/i18n';

configure({ asyncUtilTimeout: 3000 });

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
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  Element.prototype.scrollIntoView = vi.fn();
  await i18n.changeLanguage('en');
});

afterEach(async () => {
  if (!vi.isFakeTimers())
    await act(async () => {
      for (let i = 0; i < 10; i++) await Promise.resolve();
    });
  cleanup();
  clearApiCache();
  clearPolicyCache();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  console.error = realError;
  if (actWarnings.length > 0)
    throw new Error(`State updated outside act() in: ${actWarnings.join(', ')}`);
});
