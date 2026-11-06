/**
 * Google's certified consent tool (Privacy & messaging, IAB TCF), used while AdSense is on (ADR 0059). It asks
 * visitors where the EU, EEA, UK or Swiss rules apply, for AdSense and Google Analytics together. Google Analytics
 * does not read the answer itself here: the page reads it from the TCF API and loads GA only after a yes, so after a
 * "no" nothing is sent at all (Google's automatic mode would still send cookieless pings).
 */

/** TCF purposes Google Analytics needs: store information on a device (1), measure content performance (8). */
const ANALYTICS_PURPOSES = [1, 8] as const;
/** Google's vendor ID in the IAB Global Vendor List. */
const GOOGLE_VENDOR = 755;

/** outside: the rules do not apply (no question); granted / denied: the answer where they apply. */
export type ConsentDecision = 'outside' | 'granted' | 'denied';

interface TcData {
  gdprApplies?: boolean;
  eventStatus?: string;
  listenerId?: number;
  purpose?: { consents?: Record<number, boolean> };
  vendor?: { consents?: Record<number, boolean> };
}

interface GoogleConsentModeValues {
  analyticsStoragePurposeConsentStatus?: unknown;
}

/**
 * Google's ConsentModePurposeStatusEnum (Privacy & messaging API): the consent mode answer for one purpose. UNKNOWN
 * (0) means the data is not ready yet; NOT_CONFIGURED (4) means the purpose is off in the Privacy & messaging UI.
 */
const ConsentModeStatus = {
  UNKNOWN: 0,
  GRANTED: 1,
  DENIED: 2,
  NOT_APPLICABLE: 3,
  NOT_CONFIGURED: 4,
} as const;

type TcfApi = (
  command: string,
  version: number,
  callback: (data: TcData, success: boolean) => void,
  parameter?: unknown,
) => void;

declare global {
  interface Window {
    __tcfapi?: TcfApi;
    googlefc?: {
      callbackQueue?: Record<string, () => void>[];
      getGoogleConsentModeValues?: () => GoogleConsentModeValues;
      ConsentModePurposeStatusEnum?: Partial<Record<keyof typeof ConsentModeStatus, number>>;
      showRevocationMessage?: () => void;
    };
  }
}

const SCRIPT_ID = 'h2-googlefc';
const WAIT_MS = 10_000;
const POLL_MS = 250;
const PRIVACY_CHOICES_PATH = '/privacy#cookies-and-browser-storage';

function hasSensitiveQuery(search: string): boolean {
  const params = new URLSearchParams(search);
  return params.has('q') || params.has('ask');
}

/** The value Google's enum gives [name], or its documented number when the enum is not on the page. */
function statusValue(win: Window, name: keyof typeof ConsentModeStatus): number {
  return win.googlefc?.ConsentModePurposeStatusEnum?.[name] ?? ConsentModeStatus[name];
}

/**
 * The consent mode answer for analytics storage, after a TCF yes: granted or denied, or undefined while Google's tool
 * has no consent mode data yet (no answer, UNKNOWN). Fails closed: GA waits until the data is ready, and a purpose
 * that is not configured in Privacy & messaging counts as no. NOT_APPLICABLE leaves the TCF yes as it is.
 */
function analyticsStorage(win: Window): 'granted' | 'denied' | undefined {
  let status: unknown;
  try {
    status = win.googlefc?.getGoogleConsentModeValues?.().analyticsStoragePurposeConsentStatus;
  } catch {
    return undefined;
  }
  if (status === statusValue(win, 'GRANTED') || status === statusValue(win, 'NOT_APPLICABLE'))
    return 'granted';
  if (status === statusValue(win, 'DENIED') || status === statusValue(win, 'NOT_CONFIGURED'))
    return 'denied';
  return undefined;
}

function settleDecision(tc: TcData, win: Window): ConsentDecision | undefined {
  const decision = decide(tc);
  if (decision !== 'granted') return decision;
  return analyticsStorage(win);
}

/** The decision a TCF answer allows, or undefined while the tool has no answer yet. */
export function decide(tc: TcData): ConsentDecision | undefined {
  if (tc.gdprApplies === false) return 'outside';
  if (tc.eventStatus !== 'tcloaded' && tc.eventStatus !== 'useractioncomplete') return undefined;
  const purposes = ANALYTICS_PURPOSES.every((p) => tc.purpose?.consents?.[p] === true);
  return purposes && tc.vendor?.consents?.[GOOGLE_VENDOR] === true ? 'granted' : 'denied';
}

/** Google's tag for this publisher, and the frame that tells it the page is ready (Google's own snippet). */
export function loadGoogleConsent(client: string, doc: Document = document): void {
  if (doc.getElementById(SCRIPT_ID)) return;
  const script = doc.createElement('script');
  script.id = SCRIPT_ID;
  script.async = true;
  script.src = `https://fundingchoicesmessages.google.com/i/${encodeURIComponent(client.replace(/^ca-/, ''))}?ers=1`;
  doc.head.appendChild(script);
  signalPresent(doc);
}

function signalPresent(doc: Document) {
  if (doc.querySelector('iframe[name="googlefcPresent"]')) return;
  const frame = doc.createElement('iframe');
  frame.name = 'googlefcPresent';
  frame.title = 'googlefcPresent';
  frame.setAttribute('aria-hidden', 'true');
  frame.tabIndex = -1;
  frame.style.display = 'none';
  doc.body.appendChild(frame);
}

/**
 * Calls [onDecision] with every answer, the first one and each change. If the tool does not load (blocked), it
 * never calls: we cannot tell where the visitor is, so nothing loads. Returns a function that stops listening.
 */
export function watchConsent(onDecision: (d: ConsentDecision) => void, win: Window = window) {
  let stopped = false;
  let listenerId: number | undefined;
  const started = Date.now();
  const attach = () => {
    if (stopped) return;
    const api = win.__tcfapi;
    if (!api) {
      if (Date.now() - started < WAIT_MS) setTimeout(attach, POLL_MS);
      return;
    }
    api('addEventListener', 2, (tc, success) => {
      if (!success || stopped) return;
      listenerId = tc.listenerId ?? listenerId;
      const decision = settleDecision(tc, win);
      if (decision) onDecision(decision);
    });
  };
  attach();
  const readAgain = () =>
    win.__tcfapi?.('getTCData', 2, (tc, success) => {
      const decision = success && !stopped ? settleDecision(tc, win) : undefined;
      if (decision) onDecision(decision);
    });
  // A TCF yes waits for the consent mode data: read the answer again once Google's tool has it.
  win.googlefc = win.googlefc ?? {};
  win.googlefc.callbackQueue = win.googlefc.callbackQueue ?? [];
  win.googlefc.callbackQueue.push({ CONSENT_MODE_DATA_READY: readAgain });
  // Another tab may have changed the answer: read it again when this tab is seen again.
  const recheck = () => {
    if (win.document.visibilityState === 'visible') readAgain();
  };
  win.document.addEventListener('visibilitychange', recheck);
  return () => {
    stopped = true;
    win.document.removeEventListener('visibilitychange', recheck);
    if (listenerId !== undefined)
      win.__tcfapi?.('removeEventListener', 2, () => undefined, listenerId);
  };
}

/**
 * The footer's "Privacy choices": Google's tool asks the question again. Its tag must not see search words or a guide
 * question, so on such an address ([search], the app's own) the app first moves to the Privacy page with [go] (the
 * router: no new page load, so no new document gets the search address as its referrer), then asks.
 */
export async function showPrivacyChoices(
  search: string,
  go: (path: string) => void | Promise<void>,
  win: Window = window,
): Promise<void> {
  if (hasSensitiveQuery(search)) await go(PRIVACY_CHOICES_PATH);
  win.googlefc = win.googlefc ?? {};
  win.googlefc.callbackQueue = win.googlefc.callbackQueue ?? [];
  win.googlefc.callbackQueue.push({
    CONSENT_DATA_READY: () => win.googlefc?.showRevocationMessage?.(),
  });
}
