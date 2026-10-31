import { useTranslation } from 'react-i18next';
import { HowItWorks } from '@/components/HowItWorks';
import { REPO_URL } from '@/components/Layout';
import { useTitle } from '@/hooks/useTitle';
import { TextPageV2 } from '@/components/TextPageV2';

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
  return (
    <TextPageV2
      i18nKey="mission.sections"
      head={<><h1>{t('mission.title')}</h1><p className="lede">{t('mission.lede')}</p></>}
      after={<><HowItWorks />{feedback}</>}
    />
  );
}
