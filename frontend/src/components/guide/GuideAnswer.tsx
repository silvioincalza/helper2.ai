import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { GuideResponse, TrailRemoved } from '@/api/types';
import { Icon } from '@/components/Icon';
import { aiMark } from '@/lib/aiMark';
import { ComparisonTable, ExplanationBlock } from './AnswerBlocks';
import { Clarify } from './Clarify';
import { PromptBox } from './PromptBox';
import type { Refine } from './types';
import { Understood } from './Understood';

/**
 * Why an answer has no AI pick, in one short line: today's AI answers are used, the model is not available, the
 * catalog had nothing, the subject was not found, or the model found nothing that fits. Undefined: no reason beyond
 * "no pick".
 */
export function noPickReason(answer: GuideResponse, t: TFunction): string | undefined {
  if (answer.notices.some((n) => n.code === 'GUIDE_DAILY_QUOTA') && answer.status === 'LLM_UNAVAILABLE') {
    return t('guide.notice.GUIDE_DAILY_QUOTA');
  }
  if (answer.status === 'LLM_UNAVAILABLE') return t('guide.status.LLM_UNAVAILABLE');
  if (answer.status === 'NO_CANDIDATES') return t('guide.status.NO_CANDIDATES');
  const notFound = answer.notices.find((n) => n.code === 'GUIDE_SUBJECT_NOT_FOUND');
  if (notFound) return notFound.message;
  if (answer.model != null) return t('guide.noFit');
  return undefined;
}

/**
 * The research trail's queries (ADR 0044): the planner's words, each marked AI-written. None on an answer reused from
 * a similar question: they came from another asker's words, so not even the answer's own search query is shown then.
 */
function PlannedQueries({ queries, model }: { queries: string[]; model: string }) {
  const { t } = useTranslation();
  if (queries.length === 0) return <strong>{t('guide.trail.noQueries')}</strong>;
  return (
    <>
      <strong>{t('guide.trail.searchedFor')}</strong>
      <ul className="plain-list proof-trail" aria-label={t('guide.trail.queries')}>
        {queries.map((q) => <li key={q}><span {...aiMark(model)}>{q}</span></li>)}
      </ul>
      <span><span className="badge">{t('picks.aiWritten')}</span></span>
    </>
  );
}

/** One line per suggestion a check removed: the source's own title and the check, in our words. Never the model's reason. */
function RemovedList({ removed }: { removed: TrailRemoved[] }) {
  const { t } = useTranslation();
  return (
    <ul className="plain-list proof-trail proof-sub" aria-label={t('guide.trail.removed')}>
      {removed.map((r, i) => <li key={`${i}-${r.title}`}>{t('guide.trail.removedLine', { title: r.title, check: t(`guide.trail.check.${r.check}`) })}</li>)}
    </ul>
  );
}

/**
 * How the guide decided, folded under the picks bar: the steps (planned, found, chose, checked, from the response's
 * own counts), how it read the question with ✕ to ask again without a part, and the intent's own answer (comparison,
 * explanation, prompt). The picks themselves are in the result list and the preview, never repeated here; with no
 * pick, the bar says why, so this does not say it again.
 */
export function GuideAnswer({ answer, onRefine }: { answer: GuideResponse; onRefine?: Refine | undefined }) {
  const { t } = useTranslation();
  if (answer.status === 'NEEDS_CLARIFICATION' && answer.clarification) {
    return <Clarify clarification={answer.clarification} aiContent={answer.aiContent} onRefine={onRefine} />;
  }
  const kept = answer.recommendations.filter((r) => r.aiReasoned).length;
  const rejected = answer.rejected ?? 0;
  const sourcesOk = answer.sources.filter((s) => s.outcome === 'SUCCEEDED').length;
  return (
    <div className="guide-answer" data-ai-provider={answer.aiContent?.provider} data-ai-models={answer.aiContent?.models.join(' ')}>

      {answer.model ? (
        <ol className="plain-list proof-strip" aria-label={t('guide.howMade')}>
          <li>
            <span className="proof-n">{t('guide.strip.planned')}</span>
            {answer.trail ? <PlannedQueries queries={answer.trail.queries} model={answer.model.fastModel} /> : (
              <>
                <strong {...aiMark(answer.model.fastModel)}>{t('guide.searchedFor', { q: answer.searchQuery })}</strong>{' '}
                <span className="badge">{t('picks.aiWritten')}</span>
              </>
            )}
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
            {answer.trail && answer.trail.removed.length > 0 ? <RemovedList removed={answer.trail.removed} /> : (
              <span className="proof-sub">
                {rejected > 0 ? t('guide.rejected', { count: rejected }) : t(answer.trail ? 'guide.trail.noneRemoved' : 'guide.noneRejected')}
              </span>
            )}
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

      {/* With no pick the bar already says why; with picks, this says how the check went. */}
      {kept > 0 && (
        <p className={`notice ${answer.status === 'OK' ? '' : 'notice-warn'}`}>
          <Icon name={answer.status === 'OK' ? 'check' : 'info'} /> {t(`guide.status.${answer.status}`)}
        </p>
      )}

      {kept > 0 && (answer.notices ?? []).filter((n) => n.code === 'GUIDE_SUBJECT_NOT_FOUND').map((n) => (
        <p key={n.message} className="notice notice-warn"><Icon name="info" /> {n.message}</p>
      ))}

      <Understood answer={answer} onRefine={onRefine} />

      {answer.explanation && answer.explanation.sentences.length > 0 && <ExplanationBlock explanation={answer.explanation} />}
      {answer.comparison && answer.comparison.rows.length > 0 && <ComparisonTable comparison={answer.comparison} />}

      {answer.prompt && <PromptBox text={answer.prompt.text} basedOn={answer.prompt.basedOn} />}

      {answer.model && (
        <p className="muted small guide-model">
          {t('guide.answeredBy', { reasoning: answer.model.reasoningModel, fast: answer.model.fastModel, provider: answer.model.provider })}
        </p>
      )}
    </div>
  );
}
