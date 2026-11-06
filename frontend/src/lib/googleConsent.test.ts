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

  it('keeps analytics off until analytics storage consent is granted', () => {
    let listener: ((d: object, ok: boolean) => void) | undefined;
    window.googlefc = {
      getGoogleConsentModeValues: () => ({ analyticsStoragePurposeConsentStatus: 'denied' }),
    };
    window.__tcfapi = (command, _v, cb) => {
      if (command === 'addEventListener') listener = cb;
    };
    const seen: string[] = [];
    watchConsent((d) => seen.push(d));

    listener?.({ listenerId: 7, ...YES }, true);
    expect(seen).toEqual(['denied']);

    window.googlefc.getGoogleConsentModeValues = () => ({
      analyticsStoragePurposeConsentStatus: 'granted',
    });
    listener?.({ listenerId: 7, ...YES }, true);
    expect(seen).toEqual(['denied', 'granted']);
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

  it('Privacy choices redirects from search addresses before reopening', () => {
    const assign = vi.fn();
    const fake = {
      location: { search: '?q=best+mcp', assign },
      googlefc: { callbackQueue: [] as Record<string, () => void>[] },
    } as unknown as Window;
    showPrivacyChoices(fake);
    expect(assign).toHaveBeenCalledWith('/privacy#cookies-and-browser-storage');
    expect(fake.googlefc?.callbackQueue).toHaveLength(0);
  });

  it('reads the answer again when the tab is seen again (another tab may have changed it)', () => {
    let tc: object = YES;
    window.__tcfapi = (command, _v, cb) => {
      if (command === 'addEventListener' || command === 'getTCData')
        cb({ listenerId: 1, ...tc }, true);
    };
    const seen: string[] = [];
    const stop = watchConsent((d) => seen.push(d));
    tc = { ...YES, vendor: { consents: {} } };
    document.dispatchEvent(new Event('visibilitychange'));
    stop();
    document.dispatchEvent(new Event('visibilitychange'));
    expect(seen).toEqual(['granted', 'denied']);
  });
});
