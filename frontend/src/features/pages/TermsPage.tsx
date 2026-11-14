import { useTranslation } from 'react-i18next';
import '@/i18n/legal';
import { Link } from 'react-router';
import { useTitle } from '@/hooks/useTitle';
import { TextPage } from '@/features/pages/TextPage';
import { usePolicyText } from '@/hooks/usePolicy';
import { PRIVACY_CONTACT } from '@/features/pages/PrivacyPage';

export default function TermsPage() {
  const { t } = useTranslation();
  useTitle(t('nav.terms'));
  const policy = usePolicyText();
  const contact = (
    <p>
      {t('terms.contactIntro')} <a href={`mailto:${PRIVACY_CONTACT}`}>{PRIVACY_CONTACT}</a>.
    </p>
  );
  const privacy = (
    <p>
      {t('terms.privacyIntro')} <Link to="/privacy">{t('terms.privacyLink')}</Link>.
    </p>
  );
  return (
    <TextPage
      i18nKey="terms.sections"
      values={policy.values}
      numbersUnavailable={policy.failed}
      head={
        <>
          <h1>{t('terms.title')}</h1>
          <p className="updated">{t('terms.updated')}</p>
          <p className="lede">{t('terms.lede')}</p>
          {contact}
        </>
      }
      after={privacy}
    />
  );
}
