export function visitorIn(language: string, timeZone: string) {
  vi.spyOn(window.navigator, 'language', 'get').mockReturnValue(language);
  vi.stubEnv('TZ', timeZone);
}
