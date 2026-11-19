import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useMonetisation } from '@/context/MonetisationContext';
import './sponsor.css';

export const SUPPORT_WAIT_DELAY_MS = 700;

export function SupportWhileSearching({ waiting }: { waiting: boolean }) {
  const { t } = useTranslation();
  const { githubSponsorsUrl, stripePaymentUrl } = useMonetisation();
  const supportOn = Boolean(githubSponsorsUrl || stripePaymentUrl);
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    if (!waiting || !supportOn) return;
    const timer = window.setTimeout(() => setSlow(true), SUPPORT_WAIT_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [waiting, supportOn]);

  if (!waiting || !supportOn || !slow) return null;
  return (
    <aside className="h2-sponsor h2-support-wait" aria-label={t('supportWait.tag')}>
      <p className="h2-sponsor__head">
        <span className="h2-sponsor__tag">{t('supportWait.tag')}</span>
      </p>
      <p className="h2-support-wait__text">{t('supportWait.text')}</p>
      <Link to="/support" className="h2-support-wait__link">
        {t('supportWait.link')}
      </Link>
    </aside>
  );
}
