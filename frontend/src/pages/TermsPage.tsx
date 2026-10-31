import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useTitle } from '@/hooks/useTitle';
import { TextPage } from '@/components/TextPage';
import { PRIVACY_CONTACT } from '@/pages/PrivacyPage';

export default function TermsPage() {
  const { t } = useTranslation();
  useTitle(t('nav.terms'));
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
      head={<><h1>{t('terms.title')}</h1><p className="updated">{t('terms.updated')}</p><p className="lede">{t('terms.lede')}</p>{contact}</>}
      after={privacy}
    />
  );
}
