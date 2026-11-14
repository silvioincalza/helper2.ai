import { CONSENT_KEY, readConsent, writeConsent } from './consent';

describe('consent (ADR 0042)', () => {
  it('is unanswered until the visitor answers', () => {
    expect(readConsent()).toBeUndefined();
  });

  it('keeps yes or no with the date', () => {
    writeConsent(true, new Date('2026-11-05T10:00:00Z'));
    expect(readConsent()).toEqual({ analytics: true, at: '2026-11-05T10:00:00.000Z' });
    writeConsent(false);
    expect(readConsent()?.analytics).toBe(false);
  });

  it('asks again when the stored answer is from another version or broken', () => {
    window.localStorage.setItem(CONSENT_KEY, JSON.stringify({ v: 0, analytics: true, at: 'x' }));
    expect(readConsent()).toBeUndefined();
    window.localStorage.setItem(CONSENT_KEY, '{not json');
    expect(readConsent()).toBeUndefined();
  });

  it('survives storage that throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readConsent()).toBeUndefined();
    expect(writeConsent(true).analytics).toBe(true);
  });
});
