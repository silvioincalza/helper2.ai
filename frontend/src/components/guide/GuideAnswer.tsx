import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { GuideResponse } from '@/api/types';
import { Icon } from '@/components/Icon';
import { aiMark } from '@/lib/aiMark';
import { ComparisonTable, ExplanationBlock } from './AnswerBlocks';
import { Clarify } from './Clarify';
import { Pick } from './Pick';
import { PromptBox } from './PromptBox';
import type { Refine } from './types';
import { Understood } from './Understood';

/**
 * Why an answer has no AI pick, in the words the full answer uses: today's AI answers are used, the model is not
 * available, it found nothing that fits, or the subject was not found. Undefined: no reason beyond "no pick".
 */
export function noPickReason(answer: GuideResponse, t: TFunction): string | undefined {
  if (answer.notices.some((n) => n.code === 'GUIDE_DAILY_QUOTA') && answer.status === 'LLM_UNAVAILABLE') {
    return t('guide.notice.GUIDE_DAILY_QUOTA');
  }
  if (answer.status === 'LLM_UNAVAILABLE') return t('guide.status.LLM_UNAVAILABLE');
  const notFound = answer.notices.find((n) => n.code === 'GUIDE_SUBJECT_NOT_FOUND');
  if (notFound) return notFound.message;
  if (answer.model != null && answer.recommendations.length > 0) return t('guide.noFit');
  return undefined;
}

/**
 * The AI guide's answer, proof first: how the answer was made (from the response's own counts), then the
 * picks. Each AI pick shows its checked quote highlighted inside the resource's own text, the user's
 * requirements as confirmed / not confirmed, and the model's explanation folded away and labelled with the outcome of
 * the reason check (checked by a second model call, removed, or not checked).
 */
export function GuideAnswer({ answer, onRefine }: { answer: GuideResponse; onRefine?: Refine | undefined }) {
  const { t } = useTranslation();
  if (answer.status === 'NEEDS_CLARIFICATION' && answer.clarification) {
    return <Clarify clarification={answer.clarification} aiContent={answer.aiContent} onRefine={onRefine} />;
  }
  const ok = answer.status === 'OK';
  // The model ran but no pick survived (it chose none, or all were rejected): say so plainly.
  const noAiPick = answer.model != null && answer.recommendations.length > 0 && !answer.recommendations.some((r) => r.aiReasoned);
  const kept = answer.recommendations.filter((r) => r.aiReasoned).length;
  const rejected = answer.rejected ?? 0;
  const sourcesOk = answer.sources.filter((s) => s.outcome === 'SUCCEEDED').length;
  // Over the daily quota the model is skipped on purpose: say that, not "the model is not available".
  const dailyQuota = answer.notices.some((n) => n.code === 'GUIDE_DAILY_QUOTA');
  const showStatus = !(dailyQuota && answer.status === 'LLM_UNAVAILABLE');
  return (
    <section
      aria-labelledby="guide-answer-h"
      className="guide-answer"
      data-ai-provider={answer.aiContent?.provider}
      data-ai-models={answer.aiContent?.models.join(' ')}
    >
      <h2 id="guide-answer-h" className="sr-only">{t('guide.title')}</h2>

      {answer.model ? (
        <ol className="plain-list proof-strip" aria-label={t('guide.howMade')}>
          <li>
            <span className="proof-n">{t('guide.strip.planned')}</span>
            <strong {...aiMark(answer.model.fastModel)}>{t('guide.searchedFor', { q: answer.searchQuery })}</strong>{' '}
            <span className="badge">{t('picks.aiWritten')}</span>
            <span className="proof-sub">{answer.model.fastModel}</span>
          </li>
          <li>
            <span className="proof-n">{t('guide.strip.found')}</span>
            <strong>{t('guide.considered', { n: answer.candidatesConsidered })}</strong>
            <span className="proof-sub">{t('guide.sourcesAnswered', { ok: sourcesOk, all: answer.sources.length })}</span>
          </li>
          <li>
            <span className="proof-n">{t('guide.strip.chose')}</span>
            <strong>{t('guide.proposed', { count: kept + rejected })}</strong>
            <span className="proof-sub">{answer.model.reasoningModel}</span>
          </li>
          <li className={kept > 0 ? 'is-checked' : undefined}>
            <span className="proof-n">{t('guide.strip.checked')}</span>
            <strong>{kept > 0 && <Icon name="check" />}{t('guide.kept', { count: kept })}</strong>
            <span className="proof-sub">{rejected > 0 ? t('guide.rejected', { count: rejected }) : t('guide.noneRejected')}</span>
          </li>
        </ol>
      ) : (
        <p className="muted small">
          {/* The planner may have written the query even when the answering model failed (aiContent says so). */}
          <span {...(answer.aiContent ? aiMark(answer.aiContent.models[0]) : {})}>{t('guide.searchedFor', { q: answer.searchQuery })}</span>
          {answer.aiContent && <>{' '}<span className="badge">{t('picks.aiWritten')}</span></>}
          {' · '}{t('guide.considered', { n: answer.candidatesConsidered })}
        </p>
      )}

      {dailyQuota && (
        <p className="notice notice-warn">
          <Icon name="info" /> {t('guide.notice.GUIDE_DAILY_QUOTA')}
        </p>
      )}

      {showStatus && (
        <p className={`notice ${ok ? '' : 'notice-warn'}`}>
          <Icon name={ok && !noAiPick ? 'check' : answer.status === 'PARTIAL' || noAiPick ? 'info' : 'warn'} />{' '}
          {noAiPick ? t('guide.noFit') : t(`guide.status.${answer.status}`)}
        </p>
      )}

      {(answer.notices ?? []).filter((n) => n.code === 'GUIDE_SUBJECT_NOT_FOUND').map((n) => (
        <p key={n.message} className="notice notice-warn"><Icon name="info" /> {n.message}</p>
      ))}

      <Understood answer={answer} onRefine={onRefine} />

      {answer.explanation && answer.explanation.sentences.length > 0 && <ExplanationBlock explanation={answer.explanation} />}
      {answer.comparison && answer.comparison.rows.length > 0 && <ComparisonTable comparison={answer.comparison} />}

      <ol className="plain-list guide-recs">
        {answer.recommendations.map((r, i) => (
          <Pick key={r.resourceId} r={r} best={i === 0 && r.aiReasoned} />
        ))}
      </ol>

      {answer.prompt && <PromptBox text={answer.prompt.text} basedOn={answer.prompt.basedOn} />}

      {answer.model && (
        <p className="muted small guide-model">
          {t('guide.answeredBy', { reasoning: answer.model.reasoningModel, fast: answer.model.fastModel, provider: answer.model.provider })}
        </p>
      )}
    </section>
  );
}
