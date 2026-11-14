import type { TFunction } from 'i18next';
import { Fragment, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { GuideResponse, Notice, TrailRemoved } from '@/api/types';
import { Icon } from '@/ui/Icon';
import { NoticeList } from '@/ui/NoticeList';
import { aiMark } from '@/lib/aiMark';
import { backAgain, type VisitorClock } from '@/lib/format';
import { Clarify } from './Clarify';
import type { Refine } from './types';
import { Understood } from './Understood';
import { AiLabel } from '@/ui/AiLabel';

type NoPickCode =
  | 'GUIDE_DAILY_QUOTA'
  | 'GUIDE_NETWORK_QUOTA'
  | 'ALL_SOURCES_FAILED'
  | 'GUIDE_BUDGET_SPENT'
  | 'GUIDE_OUT_OF_TIME'
  | 'LLM_UNAVAILABLE'
  | 'NO_CANDIDATES'
  | 'GUIDE_SUBJECT_NOT_FOUND'
  | 'NO_FIT';

export function noPickCode(answer: GuideResponse): NoPickCode | undefined {
  const has = (code: Notice['code']) => answer.notices.some((n) => n.code === code);
  if (has('GUIDE_DAILY_QUOTA') && answer.status === 'LLM_UNAVAILABLE') return 'GUIDE_DAILY_QUOTA';
  if (has('GUIDE_NETWORK_QUOTA') && answer.status === 'LLM_UNAVAILABLE')
    return 'GUIDE_NETWORK_QUOTA';
  if (has('ALL_SOURCES_FAILED')) return 'ALL_SOURCES_FAILED';
  if (has('GUIDE_BUDGET_SPENT') && answer.status === 'LLM_UNAVAILABLE') return 'GUIDE_BUDGET_SPENT';
  if (has('GUIDE_OUT_OF_TIME') && answer.status === 'LLM_UNAVAILABLE') return 'GUIDE_OUT_OF_TIME';
  if (answer.status === 'LLM_UNAVAILABLE') return 'LLM_UNAVAILABLE';
  if (answer.status === 'NO_CANDIDATES') return 'NO_CANDIDATES';
  if (has('GUIDE_SUBJECT_NOT_FOUND')) return 'GUIDE_SUBJECT_NOT_FOUND';
  if (answer.model != null) return 'NO_FIT';
  return undefined;
}

type ComesBackCode = 'GUIDE_DAILY_QUOTA' | 'GUIDE_NETWORK_QUOTA' | 'GUIDE_BUDGET_SPENT';

const BACK_AT: Record<ComesBackCode, string> = {
  GUIDE_DAILY_QUOTA: 'guide.notice.dailyQuota',
  GUIDE_NETWORK_QUOTA: 'guide.notice.networkQuota',
  GUIDE_BUDGET_SPENT: 'guide.notice.budget',
};

function comesBackReason(
  answer: GuideResponse,
  code: ComesBackCode,
  t: TFunction,
  clock: VisitorClock,
): string {
  const retryAt = answer.notices.find((n) => n.code === code)?.retryAt;
  const back = backAgain(BACK_AT[code], retryAt, clock);
  return back ? t(back.key, { time: back.time }) : t(`guide.notice.${code}`);
}

export function noPickReason(
  answer: GuideResponse,
  t: TFunction,
  clock: VisitorClock = { now: Date.now() },
): ReactNode {
  const code = noPickCode(answer);
  switch (code) {
    case 'GUIDE_DAILY_QUOTA':
    case 'GUIDE_NETWORK_QUOTA':
    case 'GUIDE_BUDGET_SPENT':
      return comesBackReason(answer, code, t, clock);
    case 'ALL_SOURCES_FAILED':
      return t('guide.notice.ALL_SOURCES_FAILED');
    case 'GUIDE_OUT_OF_TIME':
      return t('guide.notice.GUIDE_OUT_OF_TIME');
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

function plannerModel(answer: GuideResponse): string | undefined {
  return answer.model?.fastModel ?? answer.aiContent?.models[0];
}

const SLOT = '\u0001';

function fill(t: TFunction, key: string, parts: Record<string, ReactNode>): ReactNode[] {
  const slots = Object.fromEntries(
    Object.keys(parts).map((name) => [name, `${SLOT}${name}${SLOT}`]),
  );
  return t(key, slots)
    .split(new RegExp(`${SLOT}(\\w+)${SLOT}`))
    .map((piece, i) => (i % 2 === 1 ? <Fragment key={i}>{parts[piece]}</Fragment> : piece));
}

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
      <AiLabel>{t(query ? 'guide.notice.subjectAndSearchAi' : 'guide.notice.subjectAi')}</AiLabel>
    </>
  );
}

export function foldNoticeExclusions(answer: GuideResponse, kept: number): Notice['code'][] {
  const exclude: Notice['code'][] = [
    'QUERY_EXPANDED',
    'GUIDE_DAILY_QUOTA',
    'GUIDE_NETWORK_QUOTA',
    'GUIDE_SUBJECT_NOT_FOUND',
    'GUIDE_OUT_OF_TIME',
    'GUIDE_BUDGET_SPENT',
  ];
  if (kept === 0 && noPickCode(answer) === 'ALL_SOURCES_FAILED') exclude.push('ALL_SOURCES_FAILED');
  return exclude;
}

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
        <AiLabel />
      </span>
    </>
  );
}

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
                <AiLabel />
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
              <AiLabel />
            </>
          )}
          {' · '}
          {t('guide.considered', { n: answer.candidatesConsidered })}
        </p>
      )}
      {!answer.model && searchNotices}

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
