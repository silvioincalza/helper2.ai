import { useTranslation } from 'react-i18next';
import { Icon } from '@/ui/Icon';
import { useMe } from '@/context/MeContext';
import { clockTime } from '@/lib/format';

const TEST_CEILING = 100;

export interface AnswersLeft {
  text: string;
  none: boolean;
}

export function useGuideAnswersLeft(): AnswersLeft | null {
  const { t } = useTranslation();
  const { limits } = useMe().me;
  const allowance = limits?.guideQuestions;
  if (!limits || !allowance || allowance.limit === 0) return null;
  const { limit, remaining } = allowance;
  if (remaining === 0)
    return {
      none: true,
      text: t('guide.answersLeft.none', { time: clockTime(limits.resetsAt, false) }),
    };
  if (limit >= TEST_CEILING) return null;
  return { none: false, text: t('guide.answersLeft.some', { remaining, limit }) };
}

export function GuideAnswersLeft({ id, value }: { id: string; value: AnswersLeft }) {
  return (
    <p id={id} className={`one-box-note${value.none ? ' is-none' : ''}`}>
      <Icon name="info" /> {value.text}
    </p>
  );
}
