import { useTranslation } from 'react-i18next';
import { HowItWorks, useHowItWorksToc } from '@/components/HowItWorks';
import { REPO_URL } from '@/components/Layout';
import { useTitle } from '@/hooks/useTitle';
import { TextPage } from '@/components/TextPage';
import { usePolicyText } from '@/hooks/usePolicy';

export default function MissionPage() {
  const { t } = useTranslation();
  useTitle(t('nav.mission'));
  const policy = usePolicyText();
  const howToc = useHowItWorksToc();
  const feedback = (
    <p>
      <a
        href={`${REPO_URL}/issues`}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={t('mission.feedbackLink')}
      >
        {t('mission.feedback')}
      </a>
    </p>
  );
  return (
    <TextPage
      i18nKey="mission.sections"
      values={policy.values}
      numbersUnavailable={policy.failed}
      afterToc={howToc}
      head={
        <>
          <h1>{t('mission.title')}</h1>
          <p className="lede">{t('mission.lede')}</p>
        </>
      }
      after={
        <>
          <HowItWorks values={policy.values} />
          {feedback}
        </>
      }
    />
  );
}
