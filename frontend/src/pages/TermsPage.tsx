import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { TextSections } from '@/components/TextSections';
import { useTitle } from '@/hooks/useTitle';
import { PRIVACY_CONTACT } from '@/pages/PrivacyPage';

export default function TermsPage() {
  const { t } = useTranslation();
  useTitle(t('nav.terms'));
  return (
    <article className="page text-page">
      <h1>{t('terms.title')}</h1>
      <p className="text-page__updated">{t('terms.updated')}</p>
      <p className="lede">{t('terms.lede')}</p>
      <p>
        {t('terms.contactIntro')} <a href={`mailto:${PRIVACY_CONTACT}`}>{PRIVACY_CONTACT}</a>.
      </p>
      <TextSections i18nKey="terms.sections" />
      <p className="page-part">
        {t('terms.privacyIntro')} <Link to="/privacy">{t('terms.privacyLink')}</Link>.
      </p>
    </article>
  );
}
