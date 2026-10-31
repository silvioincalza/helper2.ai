import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { TextSections } from '@/components/TextSections';
import { useTitle } from '@/hooks/useTitle';
import { TextPageV2 } from '@/components/TextPageV2';
import { NEW_DESIGN_ENABLED } from '@/lib/features';
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
  if (NEW_DESIGN_ENABLED) {
    return (
      <TextPageV2
        i18nKey="terms.sections"
        head={<><h1>{t('terms.title')}</h1><p className="updated">{t('terms.updated')}</p><p className="lede">{t('terms.lede')}</p>{contact}</>}
        after={privacy}
      />
    );
  }
  return (
    <article className="text-page">
      <h1>{t('terms.title')}</h1>
      <p className="muted small">{t('terms.updated')}</p>
      <p className="lede">{t('terms.lede')}</p>
      {contact}
      <TextSections i18nKey="terms.sections" />
      {privacy}
    </article>
  );
}
