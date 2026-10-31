import { useTranslation } from 'react-i18next';
import { useTitle } from '@/hooks/useTitle';
import { TextPage } from '@/components/TextPage';

export const PRIVACY_CONTACT = 'helper2ai@incalza.me';

export default function PrivacyPage() {
  const { t } = useTranslation();
  useTitle(t('nav.privacy'));
  const contact = (
    <p>
      {t('privacy.contactIntro')} <a href={`mailto:${PRIVACY_CONTACT}`}>{PRIVACY_CONTACT}</a>.
    </p>
  );
  return (
    <TextPage
      i18nKey="privacy.sections"
      head={<><h1>{t('privacy.title')}</h1><p className="updated">{t('privacy.updated')}</p><p className="lede">{t('privacy.lede')}</p>{contact}</>}
    />
  );
}
