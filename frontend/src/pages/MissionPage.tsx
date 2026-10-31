import { useTranslation } from 'react-i18next';
import { HowItWorks } from '@/components/HowItWorks';
import { REPO_URL } from '@/components/Layout';
import { TextSections } from '@/components/TextSections';
import { useTitle } from '@/hooks/useTitle';

export default function MissionPage() {
  const { t } = useTranslation();
  useTitle(t('nav.mission'));
  return (
    <article className="page text-page">
      <h1>{t('mission.title')}</h1>
      <p className="lede">{t('mission.lede')}</p>
      <TextSections i18nKey="mission.sections" />
      <HowItWorks />
      <p className="page-part">
        <a href={`${REPO_URL}/issues`} target="_blank" rel="noopener noreferrer" aria-label={t('mission.feedbackLink')}>
          {t('mission.feedback')}
        </a>
      </p>
    </article>
  );
}
