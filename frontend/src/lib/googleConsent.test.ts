import { decide, loadGoogleConsent, showPrivacyChoices, watchConsent } from './googleConsent';

const YES = {
  gdprApplies: true,
  eventStatus: 'useractioncomplete',
  purpose: { consents: { 1: true, 8: true } },
  vendor: { consents: { 755: true } },
};

describe("Google's consent tool (ADR 0059)", () => {
  afterEach(() => {
    delete window.__tcfapi;
    delete window.googlefc;
    document.getElementById('h2-googlefc')?.remove();
    document.querySelector('iframe[name="googlefcPresent"]')?.remove();
  });

  it('decides from the TCF answer', () => {
    expect(decide({ gdprApplies: false })).toBe('outside');
    expect(decide({ gdprApplies: true, eventStatus: 'cmpuishown' })).toBeUndefined();
    expect(decide(YES)).toBe('granted');
    expect(decide({ ...YES, eventStatus: 'tcloaded' })).toBe('granted');
    expect(decide({ ...YES, purpose: { consents: { 1: true } } })).toBe('denied');
    expect(decide({ ...YES, vendor: { consents: { 755: false } } })).toBe('denied');
  });

  it("loads Google's tag for the publisher once, with the frame it waits for", () => {
    loadGoogleConsent('ca-pub-4688630163076563');
    loadGoogleConsent('ca-pub-4688630163076563');
    const scripts = document.querySelectorAll('script#h2-googlefc');
    expect(scripts).toHaveLength(1);
    expect((scripts[0] as HTMLScriptElement).src).toBe(
      'https://fundingchoicesmessages.google.com/i/pub-4688630163076563?ers=1',
    );
    expect(document.querySelectorAll('iframe[name="googlefcPresent"]')).toHaveLength(1);
  });

  it('reports every decision, and stops listening when asked', () => {
    let listener: ((d: object, ok: boolean) => void) | undefined;
    const api = vi.fn((command: string, _v: number, cb: (d: object, ok: boolean) => void) => {
      if (command === 'addEventListener') listener = cb;
    });
    window.__tcfapi = api;
    const seen: string[] = [];
    const stop = watchConsent((d) => seen.push(d));

    listener?.({ listenerId: 7, gdprApplies: true, eventStatus: 'cmpuishown' }, true);
    listener?.({ listenerId: 7, ...YES }, true);
    listener?.({ listenerId: 7, ...YES, vendor: { consents: {} } }, true);
    listener?.({ listenerId: 7, ...YES }, false);
    stop();

    expect(seen).toEqual(['granted', 'denied']);
    expect(api).toHaveBeenLastCalledWith('removeEventListener', 2, expect.any(Function), 7);
  });

  it('waits for the tool, and never decides when it does not load', () => {
    vi.useFakeTimers();
    const seen: string[] = [];
    watchConsent((d) => seen.push(d));
    vi.advanceTimersByTime(11_000);
    window.__tcfapi = (_c, _v, cb) => cb({ gdprApplies: false }, true);
    vi.advanceTimersByTime(1_000);
    expect(seen).toEqual([]);
    vi.useRealTimers();
  });

  it('Privacy choices asks the question again through the callback queue', () => {
    const show = vi.fn();
    showPrivacyChoices();
    window.googlefc = { ...window.googlefc, showRevocationMessage: show };
    window.googlefc.callbackQueue?.[0]?.CONSENT_DATA_READY?.();
    expect(show).toHaveBeenCalledOnce();
  });
});
