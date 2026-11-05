import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '@/components/Icon';
import { useMe } from '@/context/MeContext';
import { clockTime } from '@/lib/sourceHealth';
import { aiAnswers } from '@/lib/v4Fields';

/**
 * The visitor's AI answers today, where the guide answers (ADR 0042, decision 4): "2 of 3 AI answers left today"; at
 * none, when they come back, and that search still works. It is there from the first render (GET /me), so the list
 * does not move when the answer comes. From `limits.guideQuestions` on GET /me; nothing when it
 * is null (the guide has no model and spends nothing).
 */
export function AiAnswersLeft({ answer }: { answer: object | null | undefined }) {
  const { t, i18n } = useTranslation();
  const { me, refresh } = useMe();
  // An answer may have spent one (a cached one does not): the count is read again, so it is never a guess.
  useEffect(() => {
    if (answer) refresh();
  }, [answer, refresh]);
  const left = aiAnswers(me);
  if (!left) return null;
  if (left.remaining > 0)
    return (
      <p className="muted small h2-ai-left">
        <Icon name="info" /> {t('quota.left', { remaining: left.remaining, limit: left.limit })}
      </p>
    );
  return (
    <p className="muted small h2-ai-left is-none">
      <Icon name="info" />{' '}
      {t('quota.none', { time: clockTime(left.resetsAt, i18n.language, false) })}
    </p>
  );
}
