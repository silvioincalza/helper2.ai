const ANALYTICS_PURPOSES = [1, 8] as const;
const GOOGLE_VENDOR = 755;

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
const ASKED_EVENT = 'h2:privacy-choices';

function hasSensitiveQuery(search: string): boolean {
  const params = new URLSearchParams(search);
  return params.has('q') || params.has('ask');
}

export function asksForConsent(pathname: string, search: string): boolean {
  return pathname.startsWith('/resource/') && !hasSensitiveQuery(search);
}

export function onPrivacyChoices(listener: () => void, win: Window = window): () => void {
  win.addEventListener(ASKED_EVENT, listener);
  return () => win.removeEventListener(ASKED_EVENT, listener);
}

function statusValue(win: Window, name: keyof typeof ConsentModeStatus): number {
  return win.googlefc?.ConsentModePurposeStatusEnum?.[name] ?? ConsentModeStatus[name];
}

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

export function decide(tc: TcData): ConsentDecision | undefined {
  if (tc.gdprApplies === false) return 'outside';
  if (tc.eventStatus !== 'tcloaded' && tc.eventStatus !== 'useractioncomplete') return undefined;
  const purposes = ANALYTICS_PURPOSES.every((p) => tc.purpose?.consents?.[p] === true);
  return purposes && tc.vendor?.consents?.[GOOGLE_VENDOR] === true ? 'granted' : 'denied';
}

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
  win.googlefc = win.googlefc ?? {};
  win.googlefc.callbackQueue = win.googlefc.callbackQueue ?? [];
  win.googlefc.callbackQueue.push({ CONSENT_MODE_DATA_READY: readAgain });
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

export async function showPrivacyChoices(
  search: string,
  go: (path: string) => void | Promise<void>,
  win: Window = window,
): Promise<void> {
  if (hasSensitiveQuery(search)) await go(PRIVACY_CHOICES_PATH);
  win.dispatchEvent(new Event(ASKED_EVENT));
  win.googlefc = win.googlefc ?? {};
  win.googlefc.callbackQueue = win.googlefc.callbackQueue ?? [];
  win.googlefc.callbackQueue.push({
    CONSENT_DATA_READY: () => win.googlefc?.showRevocationMessage?.(),
  });
}
