export const SIGN_IN_RETURN_KEY = 'h2.signInReturn';

const isLocalPath = (p: string) => p.startsWith('/') && !p.startsWith('//') && !p.startsWith('/\\');

function withoutSignInMarker(path: string): string {
  const url = new URL(path, 'https://local.invalid');
  url.searchParams.delete('signin');
  return url.pathname + url.search + url.hash;
}

export function rememberSignInReturn(path: string): void {
  try {
    if (isLocalPath(path))
      window.sessionStorage.setItem(SIGN_IN_RETURN_KEY, withoutSignInMarker(path));
  } catch {}
}

export function takeSignInReturn(): string | null {
  try {
    const path = window.sessionStorage.getItem(SIGN_IN_RETURN_KEY);
    window.sessionStorage.removeItem(SIGN_IN_RETURN_KEY);
    return path !== null && isLocalPath(path) ? path : null;
  } catch {
    return null;
  }
}
