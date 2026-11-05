import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useTitle } from '@/hooks/useTitle';
import { useMonetisation } from '@/context/MonetisationContext';
import '@/pages/text.css';

/**
 * Support helper2 (ADR 0057, UJ-11): what running the site costs, and two ways to give. Payment happens at the
 * provider; nothing on helper2 is locked for anyone, and nobody is asked twice.
 */
export default function SupportPage() {
  const { t } = useTranslation();
  useTitle(t('support.title'));
  const { githubSponsorsUrl, stripePaymentUrl } = useMonetisation();
  const open = githubSponsorsUrl !== null || stripePaymentUrl !== null;
  const costs = t('support.costs', { returnObjects: true }) as string[];
  return (
    <article className="h2-text-page">
      <h1>{t('support.title')}</h1>
      <p className="lede">{t('support.lede')}</p>
      <h2>{t('support.costsTitle')}</h2>
      <ul>
        {costs.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
      <h2>{t('support.howTitle')}</h2>
      {open ? (
        <>
          <ul className="plain-list h2-support__ways">
            {githubSponsorsUrl && (
              <li>
                <a
                  className="btn"
                  href={githubSponsorsUrl}
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  {t('support.github')}
                  <span className="sr-only"> {t('support.newTab')}</span>
                </a>
                <p>{t('support.githubNote')}</p>
              </li>
            )}
            {stripePaymentUrl && (
              <li>
                <a className="btn" href={stripePaymentUrl} rel="noopener">
                  {t('support.card')}
                </a>
                <p>{t('support.cardNote')}</p>
              </li>
            )}
          </ul>
          <p>{t('support.leave')}</p>
        </>
      ) : (
        <p>{t('support.closed')}</p>
      )}
      <p>
        {t('support.privacyIntro')}{' '}
        <Link to="/privacy#who-receives-data">{t('support.privacyLink')}</Link>
      </p>
    </article>
  );
}
