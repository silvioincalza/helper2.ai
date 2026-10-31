/**
 * Where to go back after sign-in: the page the visitor was on when they clicked "Sign in". GitHub returns to "/" on
 * the public host (ADR 0035), so the path waits in sessionStorage (this tab only) and is removed as soon as it is used.
 * Only a path on this site is followed: never "//host" or a full URL, so the key cannot redirect anywhere else.
 */
export const SIGN_IN_RETURN_KEY = 'h2.signInReturn';

const isLocalPath = (p: string) => p.startsWith('/') && !p.startsWith('//') && !p.startsWith('/\\');

export function rememberSignInReturn(path: string): void {
  try {
    if (isLocalPath(path)) window.sessionStorage.setItem(SIGN_IN_RETURN_KEY, path);
  } catch {
    /* storage blocked: the visitor lands on the home page, which still works */
  }
}

/** The stored path, removed at once; null when there is none or it is not a path on this site. */
export function takeSignInReturn(): string | null {
  try {
    const path = window.sessionStorage.getItem(SIGN_IN_RETURN_KEY);
    window.sessionStorage.removeItem(SIGN_IN_RETURN_KEY);
    return path !== null && isLocalPath(path) ? path : null;
  } catch {
    return null;
  }
}
