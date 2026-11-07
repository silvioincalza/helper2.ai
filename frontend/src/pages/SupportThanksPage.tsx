import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useTitle } from '@/hooks/useTitle';
import '@/pages/text.css';

/** Where the Stripe Payment Link sends a giver back (ADR 0030): a thank-you, a way back, no upsell. */
export default function SupportThanksPage() {
  const { t } = useTranslation();
  useTitle(t('support.thanksTitle'));
  return (
    <article className="h2-text-page">
      <h1>{t('support.thanksTitle')}</h1>
      <p className="lede">{t('support.thanksBody')}</p>
      <p>
        <Link to="/">{t('support.backHome')}</Link>
      </p>
    </article>
  );
}
