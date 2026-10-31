import { useTranslation } from 'react-i18next';
import { TextSections } from '@/components/TextSections';
import { useTitle } from '@/hooks/useTitle';

export const PRIVACY_CONTACT = 'helper2ai@incalza.me';

export default function PrivacyPage() {
  const { t } = useTranslation();
  useTitle(t('nav.privacy'));
  return (
    <article className="page text-page">
      <h1>{t('privacy.title')}</h1>
      <p className="text-page__updated">{t('privacy.updated')}</p>
      <p className="lede">{t('privacy.lede')}</p>
      <p>
        {t('privacy.contactIntro')} <a href={`mailto:${PRIVACY_CONTACT}`}>{PRIVACY_CONTACT}</a>.
      </p>
      <TextSections i18nKey="privacy.sections" />
    </article>
  );
}
