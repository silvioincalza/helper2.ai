import { useTranslation } from 'react-i18next';
import { Icon } from '@/components/Icon';
import { useMe } from '@/context/MeContext';

/** A limit this high is a test environment's ceiling (staging, evaluation runs), not one a visitor plans around. */
const TEST_CEILING = 100;

/** The time [iso] in the visitor's own clock (HH:mm). */
function localTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

export interface AnswersLeft {
  text: string;
  none: boolean;
}

/**
 * AI answers left today from this address (`limits.guideQuestions` on GET /me, ADR 0017), to say next to "Ask the
 * guide" before the visitor spends one. Null while /me is unknown, when the guide has no model (null), and for a test
 * environment's ceiling unless none is left.
 */
export function useGuideAnswersLeft(): AnswersLeft | null {
  const { t } = useTranslation();
  const { limits } = useMe().me;
  const allowance = limits?.guideQuestions;
  if (!limits || !allowance || allowance.limit === 0) return null;
  const { limit, remaining } = allowance;
  if (remaining === 0)
    return { none: true, text: t('guide.answersLeft.none', { time: localTime(limits.resetsAt) }) };
  if (limit >= TEST_CEILING) return null;
  return { none: false, text: t('guide.answersLeft.some', { remaining, limit }) };
}

/** The count as one quiet line: the words carry the state, not the colour. */
export function GuideAnswersLeft({ id, value }: { id: string; value: AnswersLeft }) {
  return (
    <p id={id} className={`one-box-note${value.none ? ' is-none' : ''}`}>
      <Icon name="info" /> {value.text}
    </p>
  );
}
