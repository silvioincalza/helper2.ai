import { useTranslation } from 'react-i18next';
import { HowItWorks } from '@/components/HowItWorks';
import { REPO_URL } from '@/components/Layout';
import { TextSections } from '@/components/TextSections';
import { useTitle } from '@/hooks/useTitle';
import { TextPageV2 } from '@/components/TextPageV2';
import { NEW_DESIGN_ENABLED } from '@/lib/features';

export default function MissionPage() {
  const { t } = useTranslation();
  useTitle(t('nav.mission'));
  const feedback = (
    <p>
      <a href={`${REPO_URL}/issues`} target="_blank" rel="noopener noreferrer" aria-label={t('mission.feedbackLink')}>
        {t('mission.feedback')}
      </a>
    </p>
  );
  if (NEW_DESIGN_ENABLED) {
    return (
      <TextPageV2
        i18nKey="mission.sections"
        head={<><h1>{t('mission.title')}</h1><p className="lede">{t('mission.lede')}</p></>}
        after={<><HowItWorks />{feedback}</>}
      />
    );
  }
  return (
    <article className="text-page">
      <h1>{t('mission.title')}</h1>
      <p className="lede">{t('mission.lede')}</p>
      <TextSections i18nKey="mission.sections" />
      <HowItWorks />
      {feedback}
    </article>
  );
}
