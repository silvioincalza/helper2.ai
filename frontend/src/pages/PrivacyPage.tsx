import { useTranslation } from 'react-i18next';
import { TextSections } from '@/components/TextSections';
import { useTitle } from '@/hooks/useTitle';
import { TextPageV2 } from '@/components/TextPageV2';
import { NEW_DESIGN_ENABLED } from '@/lib/features';

export const PRIVACY_CONTACT = 'helper2ai@incalza.me';

export default function PrivacyPage() {
  const { t } = useTranslation();
  useTitle(t('nav.privacy'));
  const contact = (
    <p>
      {t('privacy.contactIntro')} <a href={`mailto:${PRIVACY_CONTACT}`}>{PRIVACY_CONTACT}</a>.
    </p>
  );
  if (NEW_DESIGN_ENABLED) {
    return (
      <TextPageV2
        i18nKey="privacy.sections"
        head={<><h1>{t('privacy.title')}</h1><p className="updated">{t('privacy.updated')}</p><p className="lede">{t('privacy.lede')}</p>{contact}</>}
      />
    );
  }
  return (
    <article className="text-page">
      <h1>{t('privacy.title')}</h1>
      <p className="muted small">{t('privacy.updated')}</p>
      <p className="lede">{t('privacy.lede')}</p>
      {contact}
      <TextSections i18nKey="privacy.sections" />
    </article>
  );
}
