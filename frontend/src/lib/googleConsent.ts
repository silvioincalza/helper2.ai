/**
 * Google's certified consent tool (Privacy & messaging, IAB TCF), used while AdSense is on (ADR 0059). It asks
 * visitors where the EU, EEA, UK or Swiss rules apply, for AdSense and Google Analytics together. Google Analytics
 * does not read the answer itself here: the page reads it from the TCF API and loads GA only after a yes, so after a
 * "no" nothing is sent at all (Google's automatic mode would still send cookieless pings).
 */

/** TCF purposes Google Analytics needs: store information on a device (1), measure content performance (8). */
export const ANALYTICS_PURPOSES = [1, 8] as const;
/** Google's vendor ID in the IAB Global Vendor List. */
export const GOOGLE_VENDOR = 755;

/** outside: the rules do not apply (no question); granted / denied: the answer where they apply. */
export type ConsentDecision = 'outside' | 'granted' | 'denied';

export interface TcData {
  gdprApplies?: boolean;
  eventStatus?: string;
  listenerId?: number;
  purpose?: { consents?: Record<number, boolean> };
  vendor?: { consents?: Record<number, boolean> };
}

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
      showRevocationMessage?: () => void;
    };
  }
}

const SCRIPT_ID = 'h2-googlefc';
const WAIT_MS = 10_000;
const POLL_MS = 250;

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
      const decision = decide(tc);
      if (decision) onDecision(decision);
    });
  };
  attach();
  return () => {
    stopped = true;
    if (listenerId !== undefined)
      win.__tcfapi?.('removeEventListener', 2, () => undefined, listenerId);
  };
}

/** The footer's "Privacy choices": Google's tool asks the question again. */
export function showPrivacyChoices(win: Window = window): void {
  win.googlefc = win.googlefc ?? {};
  win.googlefc.callbackQueue = win.googlefc.callbackQueue ?? [];
  win.googlefc.callbackQueue.push({
    CONSENT_DATA_READY: () => win.googlefc?.showRevocationMessage?.(),
  });
}
