import { beforeEach, describe, expect, it } from 'vitest';
import { isSameSitePath } from './sameSitePath';
import { SIGN_IN_RETURN_KEY, rememberSignInReturn, takeSignInReturn } from './signInReturn';

const LONG_PATH = `/${'a'.repeat(10_000)}`;

const SAME_SITE = [
  '/',
  '/path',
  '/path?x=1#y',
  '/resource/abc?q=llm&type=MODEL',
  '/%2F%2Fevil.example',
  '/a//b',
  '/a\\b',
  '/path with space',
  LONG_PATH,
];

const NOT_SAME_SITE = [
  '',
  'search',
  ' /path',
  'https://evil.example',
  '//evil.example',
  '/\\evil.example',
  '/\\\\evil',
  '\\\\evil',
  '\\/evil.example',
  'javascript:alert(1)',
  '/\t/evil',
  '/\n/evil.example',
  '/\r\\evil.example',
  '\t//evil.example',
  '/path\nnext',
  '/path\u0000',
  '/path\u007f',
  '/path\u0085',
];

function returnedAfterSignIn(stored: string): string | null {
  window.sessionStorage.setItem(SIGN_IN_RETURN_KEY, stored);
  return takeSignInReturn();
}

describe('isSameSitePath', () => {
  it.each(SAME_SITE)('accepts %j', (path) => {
    expect(isSameSitePath(path)).toBe(true);
  });

  it.each(NOT_SAME_SITE)('refuses %j', (path) => {
    expect(isSameSitePath(path)).toBe(false);
  });
});

describe('the path a user returns to after sign-in', () => {
  beforeEach(() => window.sessionStorage.clear());

  it.each(SAME_SITE)('is %j when it was stored', (path) => {
    expect(returnedAfterSignIn(path)).toBe(path);
  });

  it.each(NOT_SAME_SITE)('is never %j', (path) => {
    expect(returnedAfterSignIn(path)).toBeNull();
  });

  it.each(NOT_SAME_SITE)('does not remember %j', (path) => {
    rememberSignInReturn(path);
    expect(window.sessionStorage.getItem(SIGN_IN_RETURN_KEY)).toBeNull();
  });

  it('remembers a same-site path without the sign-in marker', () => {
    rememberSignInReturn('/saved?signin=failed&q=llm#top');
    expect(takeSignInReturn()).toBe('/saved?q=llm#top');
  });

  it('is taken once', () => {
    rememberSignInReturn('/saved');
    takeSignInReturn();
    expect(takeSignInReturn()).toBeNull();
  });
});
