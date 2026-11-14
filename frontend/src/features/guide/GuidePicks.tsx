import { useId } from 'react';
import { Link, useLocation } from 'react-router';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import type { GuideResponse } from '@/api/types';
import { PicksBar, type PicksState } from '@/ui/PicksBar';
import { Icon } from '@/ui/Icon';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import type { GuideStream } from '@/hooks/useGuideStream';
import { useRetry } from '@/hooks/useRetry';
import { aiMark } from '@/lib/aiMark';
import { guidePicks } from '@/lib/selection';
import { AnswerBlocks } from './AnswerBlocks';
import { GuideAnswer, noPickReason } from './GuideAnswer';
import { progressOf, readingParts } from './reading';
import type { Refine } from './types';
import { WebSearchesLeft } from './WebSearchesLeft';
import { resourcePath } from '@/lib/searchState';
import { rowAnchor } from '@/features/search/ResultRow';
import { toFromResults, type CardHead } from '@/features/search/fromResults';
import { AiLabel } from '@/ui/AiLabel';

export function GuidePicks({
  stream,
  anchored,
  cards,
  webLine,
}: {
  stream: GuideStream;
  anchored: ReadonlySet<string>;
  cards: readonly CardHead[];
  webLine: boolean;
}) {
  const { t } = useTranslation();
  const location = useLocation();
  const { answer, error, busy, refine, previous } = stream;
  const { retry, focusRef } = useRetry(stream.retry, busy);
  const kept = guidePicks(previous);
  const stale = kept.length > 0;
  const picks = stale ? kept : guidePicks(answer);
  const cardOf = (id: string) => cards.find((c) => c.id === id);
  const state = stale ? picked(previous, picks.length) : picksState(stream, picks.length, t);
  const answered = answer !== null && answer.status !== 'NEEDS_CLARIFICATION';
  return (
    <div className="h2-results__guide">
      {stream.filtersChanged && (
        <p className="h2-results__refilter" role="status">
          {t('guide.filtersChanged')}{' '}
          <button type="button" className="btn btn-ghost" onClick={stream.askAgain}>
            {t('guide.askAgain')}
          </button>
        </p>
      )}
      {error === null && (
        <PicksBar state={state} stale={stale} titleRef={focusRef}>
          {state.kind === 'none' ? (
            answer?.model != null && <NoPickNext answer={answer} onRefine={refine} />
          ) : (
            <ol className="h2-picks__chips">
              {picks.map((p) => (
                <li key={p.resourceId} className="h2-pick">
                  {anchored.has(p.resourceId) ? (
                    <a
                      className="h2-pick-chip"
                      title={p.title}
                      href={`#${rowAnchor(p.resourceId)}`}
                    >
                      <b>#{p.rank}</b> <span className="h2-pick-chip__title">{p.title}</span>
                    </a>
                  ) : (
                    <Link
                      className="h2-pick-chip"
                      title={p.title}
                      to={resourcePath(p.resourceId, p.title)}
                      state={toFromResults(location, p, cardOf(p.resourceId))}
                    >
                      <b>#{p.rank}</b> <span className="h2-pick-chip__title">{p.title}</span>
                    </Link>
                  )}
                  {p.reason && p.reasonCheck !== 'REMOVED' && (
                    <p className="h2-pick__reason" {...aiMark()}>
                      {p.reason}
                    </p>
                  )}
                </li>
              ))}
            </ol>
          )}
        </PicksBar>
      )}
      {busy && error === null
        ? webLine && (
            <p className="muted small h2-web-left" aria-hidden="true">
              &nbsp;
            </p>
          )
        : !busy && <WebSearchesLeft feature="guide" value={answer?.webSearches} />}
      {error !== null && (
        <StatePanel
          tone="error"
          level={2}
          title={guideError(error, t)}
          next={<RetryButton onClick={retry} />}
        />
      )}
      {answer && answer.status === 'NEEDS_CLARIFICATION' && (
        <GuideAnswer answer={answer} onRefine={refine} />
      )}
      {busy && error === null && <div className="h2-results__full-slot" aria-hidden="true" />}
      {answered && <AnswerBlocks answer={answer} />}
      {answered && <FullAnswer answer={answer} onRefine={refine} />}
    </div>
  );
}

function picksState(stream: GuideStream, pickCount: number, t: TFunction): PicksState {
  if (!stream.busy) {
    if (stream.answer?.status === 'NEEDS_CLARIFICATION') return { kind: 'clarify' };
    if (pickCount > 0) return picked(stream.answer, pickCount);
    return { kind: 'none', reason: stream.answer ? noPickReason(stream.answer, t) : undefined };
  }
  const { reading, candidates, activity } = progressOf(stream.steps);
  const understood = reading
    ? readingParts(reading, new Date().toISOString().slice(0, 10), t).map((p) => p.label)
    : [];
  return { kind: 'reading', candidates, understood, activity };
}

function picked(answer: GuideResponse | null, count: number): PicksState {
  return { kind: 'picks', model: answer?.model?.reasoningModel ?? null, count };
}

function NoPickNext({ answer, onRefine }: { answer: GuideResponse; onRefine: Refine }) {
  const { t } = useTranslation();
  const requirements = readingParts(answer, answer.generatedAt.slice(0, 10), t).filter(
    (p) => p.ai && p.drop,
  );
  const rephrase = () => {
    const box = document.getElementById('q');
    if (box instanceof HTMLTextAreaElement) {
      box.focus();
      box.select();
    }
  };
  return (
    <div className="h2-picks__next">
      {requirements.map((p) => (
        <button
          key={p.key}
          type="button"
          className="btn btn-on-term"
          onClick={() => onRefine({ drop: [p.drop!] })}
        >
          <span>
            {t('picks.askWithout')} “<span {...aiMark()}>{p.label}</span>”
          </span>
        </button>
      ))}
      <button type="button" className="btn btn-on-term" onClick={rephrase}>
        {t('picks.rephrase')}
      </button>
      {requirements.length > 0 && <AiLabel kind="read">{t('guide.requirementsAi')}</AiLabel>}
    </div>
  );
}

function FullAnswer({ answer, onRefine }: { answer: GuideResponse; onRefine: Refine }) {
  const { t } = useTranslation();
  const summaryId = useId();
  return (
    <details className="h2-results__full">
      <summary id={summaryId} className="h2-fold-toggle">
        {t('results.fullAnswer')} <Icon name="chevron" />
      </summary>
      <section aria-labelledby={summaryId}>
        <GuideAnswer answer={answer} onRefine={onRefine} />
      </section>
    </details>
  );
}

function guideError(err: unknown, t: TFunction): string {
  if (!(err instanceof ApiError)) return t('guide.error');
  if (err.stream) return t(`guide.streamFailure.${err.stream}`);
  return err.problem?.detail ?? t('guide.error');
}
