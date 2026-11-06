import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router';
import { useMonetisation } from '@/context/MonetisationContext';
import { isAnalyticsPath, startAnalytics, stopAnalytics, trackPage } from '@/lib/analytics';
import { CONSENT_KEY, readConsent, writeConsent, type Consent } from '@/lib/consent';
import { loadGoogleConsent, watchConsent, type ConsentDecision } from '@/lib/googleConsent';
import '@/components/consent.css';

const OPEN_EVENT = 'h2:consent-open';

/** The footer's "Cookie settings": asks the analytics question again. */
export function openConsentSettings() {
  window.dispatchEvent(new Event(OPEN_EVENT));
}

/**
 * Google Analytics only after a yes (ADR 0057). While analytics is off on the server, nothing shows and nothing
 * loads. The banner never covers the search box, and search works before the visitor answers. "Reject" and
 * "Accept" are the same size and style. Nothing loads on the back office.
 */
export function AnalyticsConsent() {
  const { adsenseClient } = useMonetisation();
  return adsenseClient ? <GoogleConsent client={adsenseClient} /> : <OwnConsentBar />;
}

/**
 * While AdSense is on (ADR 0059): Google's consent tool asks, for AdSense and Google Analytics together, where the
 * EU, EEA, UK or Swiss rules apply. GA loads after a yes, and without a question where the rules do not apply; a
 * "no", or a later withdrawal, turns it off. Nothing loads on the back office. Google draws the question.
 */
function GoogleConsent({ client }: { client: string }) {
  const { ga4MeasurementId } = useMonetisation();
  const { pathname, search } = useLocation();
  const public_ = isAnalyticsPath(pathname);
  // Google's tag sees the page address: it never loads on an address with search words or a guide question. Once
  // loaded on another page, it stays for the visit.
  const quiet = new URLSearchParams(search).has('q') || new URLSearchParams(search).has('ask');
  const [decision, setDecision] = useState<ConsentDecision | undefined>();

  useEffect(() => {
    if (public_ && !quiet) loadGoogleConsent(client);
  }, [client, public_, quiet]);

  // Listens again when the tag may just have loaded (a visit that started on a search address).
  useEffect(() => {
    if (!public_) return;
    return watchConsent(setDecision);
  }, [public_, quiet]);

  const allowed = public_ && (decision === 'granted' || decision === 'outside');

  useEffect(() => {
    if (!ga4MeasurementId) return;
    if (allowed) startAnalytics(ga4MeasurementId);
    else if (decision === 'denied') stopAnalytics(ga4MeasurementId);
  }, [allowed, decision, ga4MeasurementId]);

  useEffect(() => {
    if (allowed && ga4MeasurementId) trackPage(pathname);
  }, [allowed, ga4MeasurementId, pathname]);

  return null;
}

/** Our own question, while only Google Analytics is on (ADR 0057). */
function OwnConsentBar() {
  const { t } = useTranslation();
  const { ga4MeasurementId } = useMonetisation();
  const { pathname } = useLocation();
  const [consent, setConsent] = useState<Consent | undefined>(() => readConsent());
  const [asking, setAsking] = useState(false);

  useEffect(() => {
    const open = () => setAsking(true);
    window.addEventListener(OPEN_EVENT, open);
    return () => window.removeEventListener(OPEN_EVENT, open);
  }, []);

  // An answer given in another tab counts here too: a withdrawal there stops the tag already loaded in this tab.
  useEffect(() => {
    const follow = (e: StorageEvent) => {
      if (e.key !== CONSENT_KEY && e.key !== null) return;
      const next = readConsent();
      setConsent(next);
      if (next?.analytics !== true && ga4MeasurementId) stopAnalytics(ga4MeasurementId);
    };
    window.addEventListener('storage', follow);
    return () => window.removeEventListener('storage', follow);
  }, [ga4MeasurementId]);

  const on = ga4MeasurementId !== null && isAnalyticsPath(pathname);
  const granted = on && consent?.analytics === true;

  useEffect(() => {
    if (granted && ga4MeasurementId) startAnalytics(ga4MeasurementId);
  }, [granted, ga4MeasurementId]);

  useEffect(() => {
    if (granted) trackPage(pathname);
  }, [granted, pathname]);

  if (!on || (consent && !asking)) return null;

  const answer = (analytics: boolean) => {
    setConsent(writeConsent(analytics));
    setAsking(false);
    if (!analytics && ga4MeasurementId) stopAnalytics(ga4MeasurementId);
  };

  return (
    <section className="h2-consent" aria-labelledby="h2-consent-title">
      <h2 id="h2-consent-title" className="h2-consent__title">
        {t('consent.title')}
      </h2>
      <p className="h2-consent__text">
        {t('consent.text')}{' '}
        <Link to="/privacy#cookies-and-browser-storage">{t('consent.more')}</Link>
      </p>
      <div className="h2-consent__actions">
        <button type="button" className="btn h2-consent__btn" onClick={() => answer(false)}>
          {t('consent.reject')}
        </button>
        <button type="button" className="btn h2-consent__btn" onClick={() => answer(true)}>
          {t('consent.accept')}
        </button>
      </div>
    </section>
  );
}
