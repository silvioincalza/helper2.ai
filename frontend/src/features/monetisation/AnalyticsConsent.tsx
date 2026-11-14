import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router';
import { useMonetisation } from '@/context/MonetisationContext';
import { isAnalyticsPath, startAnalytics, stopAnalytics, trackPage } from '@/lib/analytics';
import { CONSENT_KEY, readConsent, writeConsent, type Consent } from '@/lib/consent';
import {
  asksForConsent,
  loadGoogleConsent,
  onPrivacyChoices,
  watchConsent,
  type ConsentDecision,
} from '@/lib/googleConsent';
import '@/features/monetisation/consent.css';

const OPEN_EVENT = 'h2:consent-open';

export function openConsentSettings() {
  window.dispatchEvent(new Event(OPEN_EVENT));
}

export function AnalyticsConsent() {
  const { adsenseClient } = useMonetisation();
  return adsenseClient ? <GoogleConsent client={adsenseClient} /> : <OwnConsentBar />;
}

function GoogleConsent({ client }: { client: string }) {
  const { ga4MeasurementId } = useMonetisation();
  const { pathname, search } = useLocation();
  const public_ = isAnalyticsPath(pathname);
  const [toolWanted, setToolWanted] = useState(false);
  const [decision, setDecision] = useState<ConsentDecision | undefined>();

  if (public_ && !toolWanted && asksForConsent(pathname, search)) setToolWanted(true);

  useEffect(() => onPrivacyChoices(() => setToolWanted(true)), []);

  useEffect(() => {
    if (!public_ || !toolWanted) return;
    loadGoogleConsent(client);
    return watchConsent(setDecision);
  }, [client, public_, toolWanted]);

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
