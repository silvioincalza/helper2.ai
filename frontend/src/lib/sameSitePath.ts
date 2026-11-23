const SAME_SITE_PATH = /^\/(?![/\\])\P{Cc}*$/u;

export function isSameSitePath(path: string): boolean {
  return SAME_SITE_PATH.test(path);
}
