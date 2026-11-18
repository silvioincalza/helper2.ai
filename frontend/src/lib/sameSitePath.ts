const SAME_SITE_PATH = /^\/(?![/\\])/;

export function isSameSitePath(path: string): boolean {
  return SAME_SITE_PATH.test(path);
}
