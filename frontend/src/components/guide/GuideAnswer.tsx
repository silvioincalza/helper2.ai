import type { TFunction } from 'i18next';
import { Fragment, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { GuideResponse, Notice, TrailRemoved } from '@/api/types';
import { Icon } from '@/components/Icon';
import { NoticeList } from '@/components/ui/NoticeList';
import { aiMark } from '@/lib/aiMark';
import { ComparisonTable, ExplanationBlock } from './AnswerBlocks';
import { Clarify } from './Clarify';
import { PromptBox } from './PromptBox';
import type { Refine } from './types';
import { Understood } from './Understood';

/** Why an answer has no AI pick, as one code. Order: quota, then no source answered, then model down, then empty catalog. */
type NoPickCode =
  | 'GUIDE_DAILY_QUOTA'
  | 'ALL_SOURCES_FAILED'
  | 'LLM_UNAVAILABLE'
  | 'NO_CANDIDATES'
  | 'GUIDE_SUBJECT_NOT_FOUND'
  | 'NO_FIT';

export function noPickCode(answer: GuideResponse): NoPickCode | undefined {
  const has = (code: Notice['code']) => answer.notices.some((n) => n.code === code);
  if (has('GUIDE_DAILY_QUOTA') && answer.status === 'LLM_UNAVAILABLE') return 'GUIDE_DAILY_QUOTA';
  if (has('ALL_SOURCES_FAILED')) return 'ALL_SOURCES_FAILED';
  if (answer.status === 'LLM_UNAVAILABLE') return 'LLM_UNAVAILABLE';
  if (answer.status === 'NO_CANDIDATES') return 'NO_CANDIDATES';
  if (has('GUIDE_SUBJECT_NOT_FOUND')) return 'GUIDE_SUBJECT_NOT_FOUND';
  if (answer.model != null) return 'NO_FIT';
  return undefined;
}

/**
 * Why an answer has no AI pick, in one short line: today's AI answers are used, no source answered the search, the
 * model is not available, the catalog had nothing, the subject was not found, or the model found nothing that fits.
 * Undefined: no reason beyond "no pick".
 */
export function noPickReason(answer: GuideResponse, t: TFunction): ReactNode {
  switch (noPickCode(answer)) {
    case 'GUIDE_DAILY_QUOTA':
      return t('guide.notice.GUIDE_DAILY_QUOTA');
    case 'ALL_SOURCES_FAILED':
      return t('guide.notice.ALL_SOURCES_FAILED');
    case 'LLM_UNAVAILABLE':
      return t('guide.status.LLM_UNAVAILABLE');
    case 'NO_CANDIDATES':
      return t('guide.status.NO_CANDIDATES');
    case 'GUIDE_SUBJECT_NOT_FOUND': {
      const notice = answer.notices.find((n) => n.code === 'GUIDE_SUBJECT_NOT_FOUND')!;
      return <SubjectNotFound notice={notice} model={plannerModel(answer)} />;
    }
    case 'NO_FIT':
      return t('guide.noFit');
    default:
      return undefined;
  }
}

/** The model that read the question: the planner (fast model), when one ran. */
function plannerModel(answer: GuideResponse): string | undefined {
  return answer.model?.fastModel ?? answer.aiContent?.models[0];
}

const SLOT = '\u0001';

/**
 * A translated sentence with [parts] put in its {{name}} slots as elements. The values never pass through the
 * translation, so a name stays plain text whatever it holds.
 */
function fill(t: TFunction, key: string, parts: Record<string, ReactNode>): ReactNode[] {
  const slots = Object.fromEntries(
    Object.keys(parts).map((name) => [name, `${SLOT}${name}${SLOT}`]),
  );
  return t(key, slots)
    .split(new RegExp(`${SLOT}(\\w+)${SLOT}`))
    .map((piece, i) => (i % 2 === 1 ? <Fragment key={i}>{parts[piece]}</Fragment> : piece));
}

/**
 * "X was not found in our sources; showing tools for: Y". The sentence is ours; the name and the search are the
 * planner's words, so each is marked AI-written and the line carries the label (ADR 0018). The server's message is
 * never shown. No subject: a date that was not found, and the line says it without the names.
 */
function SubjectNotFound({ notice, model }: { notice: Notice; model?: string | undefined }) {
  const { t } = useTranslation();
  const { subject, query } = notice;
  if (!subject) return <>{t('guide.notice.GUIDE_SUBJECT_NOT_FOUND')}</>;
  const marked = (text: string) => <span {...aiMark(model)}>{text}</span>;
  const sentence = query
    ? fill(t, 'guide.notice.subjectNotFoundShowing', {
        subject: marked(subject),
        query: marked(query),
      })
    : fill(t, 'guide.notice.subjectNotFound', { subject: marked(subject) });
  return (
    <>
      {sentence}{' '}
      <span className="badge">
        {t(query ? 'guide.notice.subjectAndSearchAi' : 'guide.notice.subjectAi')}
      </span>
    </>
  );
}

/**
 * The search notices to list under "found": not the query expansion (the reading shows it), not the guide's own
 * notices (the bar and the status line say them), and not "no source answered" when it is already the bar's reason.
 */
export function foldNoticeExclusions(answer: GuideResponse, kept: number): Notice['code'][] {
  const exclude: Notice['code'][] = [
    'QUERY_EXPANDED',
    'GUIDE_DAILY_QUOTA',
    'GUIDE_SUBJECT_NOT_FOUND',
  ];
  if (kept === 0 && noPickCode(answer) === 'ALL_SOURCES_FAILED') exclude.push('ALL_SOURCES_FAILED');
  return exclude;
}

/**
 * The research trail's queries (ADR 0018): the planner's words, each marked AI-written. None on an answer reused from
 * a similar question: they came from another asker's words, so not even the answer's own search query is shown then.
 */
function PlannedQueries({ queries, model }: { queries: string[]; model: string }) {
  const { t } = useTranslation();
  if (queries.length === 0) return <strong>{t('guide.trail.noQueries')}</strong>;
  return (
    <>
      <strong>{t('guide.trail.searchedFor')}</strong>
      <ul className="plain-list proof-trail" aria-label={t('guide.trail.queries')}>
        {queries.map((q) => (
          <li key={q}>
            <span {...aiMark(model)}>{q}</span>
          </li>
        ))}
      </ul>
      <span>
        <span className="badge">{t('picks.aiWritten')}</span>
      </span>
    </>
  );
}

/** One line per suggestion a check removed: the source's own title and the check, in our words. Never the model's reason. */
function RemovedList({ removed }: { removed: TrailRemoved[] }) {
  const { t } = useTranslation();
  return (
    <ul className="plain-list proof-trail proof-sub" aria-label={t('guide.trail.removed')}>
      {removed.map((r, i) => (
        <li key={`${i}-${r.title}`}>
          {t('guide.trail.removedLine', {
            title: r.title,
            check: t(`guide.trail.check.${r.check}`),
          })}
        </li>
      ))}
    </ul>
  );
}

/**
 * How the guide decided, folded under the picks bar: the steps (planned, found, chose, checked, from the response's
 * own counts), how it read the question with ✕ to ask again without a part, and the intent's own answer (comparison,
 * explanation, prompt). The picks themselves are in the result list and the preview, never repeated here; with no
 * pick, the bar says why, so this does not say it again.
 */
export function GuideAnswer({
  answer,
  onRefine,
}: {
  answer: GuideResponse;
  onRefine?: Refine | undefined;
}) {
  const { t } = useTranslation();
  if (answer.status === 'NEEDS_CLARIFICATION' && answer.clarification) {
    return (
      <Clarify
        clarification={answer.clarification}
        aiContent={answer.aiContent}
        onRefine={onRefine}
      />
    );
  }
  const kept = answer.recommendations.filter((r) => r.aiReasoned).length;
  const rejected = answer.rejected ?? 0;
  const sourcesOk = answer.sources.filter((s) => s.outcome === 'SUCCEEDED').length;
  const searchNotices = (
    <NoticeList notices={answer.notices} exclude={foldNoticeExclusions(answer, kept)} />
  );
  return (
    <div
      className="guide-answer"
      data-ai-provider={answer.aiContent?.provider}
      data-ai-models={answer.aiContent?.models.join(' ')}
    >
      {answer.model ? (
        <ol className="plain-list proof-strip" aria-label={t('guide.howMade')}>
          <li>
            <span className="proof-n">{t('guide.strip.planned')}</span>
            {answer.trail ? (
              <PlannedQueries queries={answer.trail.queries} model={answer.model.fastModel} />
            ) : (
              <>
                <strong>
                  {fill(t, 'guide.searchedFor', {
                    q: <span {...aiMark(answer.model.fastModel)}>{answer.searchQuery}</span>,
                  })}
                </strong>{' '}
                <span className="badge">{t('picks.aiWritten')}</span>
              </>
            )}
            <span className="proof-sub">{answer.model.fastModel}</span>
          </li>
          <li>
            <span className="proof-n">{t('guide.strip.found')}</span>
            <strong>{t('guide.considered', { n: answer.candidatesConsidered })}</strong>
            <span className="proof-sub">
              {t('guide.sourcesAnswered', { ok: sourcesOk, all: answer.sources.length })}
            </span>
            {searchNotices}
          </li>
          <li>
            <span className="proof-n">{t('guide.strip.chose')}</span>
            <strong>{t('guide.proposed', { count: kept + rejected })}</strong>
            <span className="proof-sub">{answer.model.reasoningModel}</span>
          </li>
          <li className={kept > 0 ? 'is-checked' : undefined}>
            <span className="proof-n">{t('guide.strip.checked')}</span>
            <strong>
              {kept > 0 && <Icon name="check" />}
              {t('guide.kept', { count: kept })}
            </strong>
            {answer.trail && answer.trail.removed.length > 0 ? (
              <RemovedList removed={answer.trail.removed} />
            ) : (
              <span className="proof-sub">
                {rejected > 0
                  ? t('guide.rejected', { count: rejected })
                  : t(answer.trail ? 'guide.trail.noneRemoved' : 'guide.noneRejected')}
              </span>
            )}
          </li>
        </ol>
      ) : (
        <p className="muted small">
          {/* The planner may have written the query even when the answering model failed (aiContent says so). */}
          {fill(t, 'guide.searchedFor', {
            q: (
              <span {...(answer.aiContent ? aiMark(answer.aiContent.models[0]) : {})}>
                {answer.searchQuery}
              </span>
            ),
          })}
          {answer.aiContent && (
            <>
              {' '}
              <span className="badge">{t('picks.aiWritten')}</span>
            </>
          )}
          {' · '}
          {t('guide.considered', { n: answer.candidatesConsidered })}
        </p>
      )}
      {/* Without the steps, the search notices follow the one line; never inside an AI-marked element (ADR 0018). */}
      {!answer.model && searchNotices}

      {/* With no pick the bar already says why; with picks, this says how the check went. */}
      {kept > 0 && (
        <p className={`notice ${answer.status === 'OK' ? '' : 'notice-warn'}`}>
          <Icon name={answer.status === 'OK' ? 'check' : 'info'} />{' '}
          {t(`guide.status.${answer.status}`)}
        </p>
      )}

      {kept > 0 &&
        (answer.notices ?? [])
          .filter((n) => n.code === 'GUIDE_SUBJECT_NOT_FOUND')
          .map((n, i) => (
            <p key={`${i}-${n.subject ?? ''}`} className="notice notice-warn">
              <Icon name="info" /> <SubjectNotFound notice={n} model={plannerModel(answer)} />
            </p>
          ))}

      <Understood answer={answer} onRefine={onRefine} />

      {answer.explanation && answer.explanation.sentences.length > 0 && (
        <ExplanationBlock explanation={answer.explanation} />
      )}
      {answer.comparison && answer.comparison.rows.length > 0 && (
        <ComparisonTable comparison={answer.comparison} />
      )}

      {answer.prompt && <PromptBox text={answer.prompt.text} basedOn={answer.prompt.basedOn} />}

      {answer.model && (
        <p className="muted small guide-model">
          {t('guide.answeredBy', {
            reasoning: answer.model.reasoningModel,
            fast: answer.model.fastModel,
            provider: answer.model.provider,
          })}
        </p>
      )}
    </div>
  );
}
