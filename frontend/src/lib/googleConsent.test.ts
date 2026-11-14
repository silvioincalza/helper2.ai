import {
  asksForConsent,
  decide,
  loadGoogleConsent,
  onPrivacyChoices,
  showPrivacyChoices,
  watchConsent,
} from './googleConsent';

const YES = {
  gdprApplies: true,
  eventStatus: 'useractioncomplete',
  purpose: { consents: { 1: true, 8: true } },
  vendor: { consents: { 755: true } },
};

describe("Google's consent tool (ADR 0042)", () => {
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
    window.googlefc = {
      getGoogleConsentModeValues: () => ({ analyticsStoragePurposeConsentStatus: 1 }),
    };
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

  const GRANTED = 1;
  const DENIED = 2;
  const NOT_APPLICABLE = 3;
  const NOT_CONFIGURED = 4;

  function withConsentMode(status: unknown) {
    let listener: ((d: object, ok: boolean) => void) | undefined;
    window.googlefc = {
      ...window.googlefc,
      getGoogleConsentModeValues: () => ({ analyticsStoragePurposeConsentStatus: status }),
    };
    window.__tcfapi = (command, _v, cb) => {
      if (command === 'addEventListener') listener = cb;
    };
    const seen: string[] = [];
    watchConsent((d) => seen.push(d));
    listener?.({ listenerId: 7, ...YES }, true);
    return seen;
  }

  it("after a TCF yes, Google's consent mode number for analytics storage decides", () => {
    expect(withConsentMode(GRANTED)).toEqual(['granted']);
    delete window.googlefc;
    expect(withConsentMode(DENIED)).toEqual(['denied']);
    delete window.googlefc;
    expect(withConsentMode(NOT_APPLICABLE)).toEqual(['granted']);
  });

  it('fails closed: no consent mode data yet means no decision, a purpose not configured means no', () => {
    expect(withConsentMode(undefined)).toEqual([]);
    delete window.googlefc;
    expect(withConsentMode(0)).toEqual([]);
    delete window.googlefc;
    expect(withConsentMode(NOT_CONFIGURED)).toEqual(['denied']);
    delete window.googlefc;
    expect(withConsentMode('granted')).toEqual([]);
  });

  it("uses Google's enum when the page has it", () => {
    window.googlefc = { ConsentModePurposeStatusEnum: { GRANTED: 11, DENIED: 12 } };
    expect(withConsentMode(11)).toEqual(['granted']);
    window.googlefc = { ConsentModePurposeStatusEnum: { GRANTED: 11, DENIED: 12 } };
    expect(withConsentMode(1)).toEqual([]);
  });

  it('a TCF yes that waited for consent mode is read again when the data is ready', () => {
    let status: unknown = 0;
    let tc: object = YES;
    window.__tcfapi = (command, _v, cb) => {
      if (command === 'addEventListener' || command === 'getTCData')
        cb({ listenerId: 1, ...tc }, true);
    };
    window.googlefc = {
      getGoogleConsentModeValues: () => ({ analyticsStoragePurposeConsentStatus: status }),
    };
    const seen: string[] = [];
    const stop = watchConsent((d) => seen.push(d));
    expect(seen).toEqual([]);

    status = GRANTED;
    for (const entry of window.googlefc.callbackQueue ?? []) entry.CONSENT_MODE_DATA_READY?.();
    expect(seen).toEqual(['granted']);

    stop();
    tc = { ...YES, vendor: { consents: {} } };
    for (const entry of window.googlefc.callbackQueue ?? []) entry.CONSENT_MODE_DATA_READY?.();
    expect(seen).toEqual(['granted']);
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

  it('asks on a resource page only, and never on an address with search words or a guide question', () => {
    expect(asksForConsent('/resource/11111111-1111-5111-8111-111111111111', '')).toBe(true);
    expect(asksForConsent('/resource/11111111-1111-5111-8111-111111111111/acme', '')).toBe(true);
    expect(asksForConsent('/resource/11111111-1111-5111-8111-111111111111', '?q=agents')).toBe(
      false,
    );
    expect(asksForConsent('/resource/11111111-1111-5111-8111-111111111111', '?ask=which')).toBe(
      false,
    );
    for (const path of ['/', '/search', '/news', '/events', '/saved', '/privacy', '/resources'])
      expect(asksForConsent(path, '')).toBe(false);
  });

  it('Privacy choices tells the page to load the tool, after the move away from a search address', async () => {
    const order: string[] = [];
    const stop = onPrivacyChoices(() => order.push('load the tool'));
    await showPrivacyChoices('?q=best+mcp', async (path) => {
      order.push(`go ${path}`);
    });
    stop();
    await showPrivacyChoices('', () => undefined);
    expect(order).toEqual(['go /privacy#cookies-and-browser-storage', 'load the tool']);
  });

  it('Privacy choices does not load the tool when the move inside the app fails', async () => {
    const asked = vi.fn();
    const stop = onPrivacyChoices(asked);
    await expect(
      showPrivacyChoices('?q=agents', () => Promise.reject(new Error('blocked'))),
    ).rejects.toThrow('blocked');
    stop();
    expect(asked).not.toHaveBeenCalled();
  });

  it('Privacy choices asks the question again through the callback queue', async () => {
    const show = vi.fn();
    const go = vi.fn();
    await showPrivacyChoices('', go);
    expect(go).not.toHaveBeenCalled();
    window.googlefc = { ...window.googlefc, showRevocationMessage: show };
    window.googlefc.callbackQueue?.[0]?.CONSENT_DATA_READY?.();
    expect(show).toHaveBeenCalledOnce();
  });

  it('Privacy choices on a search address moves inside the app first, then asks', async () => {
    const order: string[] = [];
    const go = vi.fn(async (path: string) => {
      order.push(`go ${path} (queue ${window.googlefc?.callbackQueue?.length ?? 0})`);
    });
    await showPrivacyChoices('?q=best+mcp', go);
    order.push(`queue ${window.googlefc?.callbackQueue?.length ?? 0}`);
    expect(order).toEqual(['go /privacy#cookies-and-browser-storage (queue 0)', 'queue 1']);
    await showPrivacyChoices('?ask=which+tool', go);
    expect(go).toHaveBeenCalledTimes(2);
  });

  it('Privacy choices does not ask when the move inside the app fails', async () => {
    await expect(
      showPrivacyChoices('?q=agents', () => Promise.reject(new Error('blocked'))),
    ).rejects.toThrow('blocked');
    expect(window.googlefc?.callbackQueue ?? []).toHaveLength(0);
  });

  it('reads the answer again when the tab is seen again (another tab may have changed it)', () => {
    let tc: object = YES;
    window.__tcfapi = (command, _v, cb) => {
      if (command === 'addEventListener' || command === 'getTCData')
        cb({ listenerId: 1, ...tc }, true);
    };
    window.googlefc = {
      getGoogleConsentModeValues: () => ({ analyticsStoragePurposeConsentStatus: 1 }),
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
