import { useId } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import type { GuideResponse } from '@/api/types';
import { PicksBar, type PicksState } from '@/components/ui/PicksBar';
import { Icon } from '@/components/Icon';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import type { GuideStream } from '@/hooks/useGuideStream';
import { useRetry } from '@/hooks/useRetry';
import { aiMark } from '@/lib/aiMark';
import { guidePicks } from '@/lib/selection';
import { GuideAnswer, noPickReason } from './GuideAnswer';
import { progressOf, readingParts } from './reading';
import type { Refine } from './types';
import { AiAnswersLeft } from './AiAnswersLeft';
import { WebSearchesLeft } from './WebSearchesLeft';
import { resourcePath } from '@/lib/searchState';

/**
 * The picks bar for the question in the URL: reading, then the picks, or one line when there is none. While the guide
 * works, the line says how it read the question and how many results it reads, as the steps arrive. With no AI pick
 * the line says why in a few words (ADR 0040, 6), with the next steps: ask again without one of the requirements, or
 * rephrase. How the guide decided stays folded under the bar. A pick that is not on this page of results links to its
 * own page. Try again moves focus to the bar's heading. [webLine] keeps the place of "web searches left" while the
 * guide reads.
 */
export function GuidePicks({
  stream,
  loaded,
  webLine,
}: {
  stream: GuideStream;
  loaded: ReadonlySet<string>;
  webLine: boolean;
}) {
  const { t } = useTranslation();
  const { answer, error, busy, refine } = stream;
  const { retry, focusRef } = useRetry(stream.retry, busy);
  const [, setSp] = useSearchParams();
  const picks = guidePicks(answer);
  const select = (id: string) =>
    setSp(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set('id', id);
        return next;
      },
      { replace: true, preventScrollReset: true },
    );
  const state = picksState(stream, picks.length, t);
  const answered = answer !== null && answer.status !== 'NEEDS_CLARIFICATION';
  return (
    <div className="h2-results__guide">
      {error === null && (
        <PicksBar state={state} titleRef={focusRef}>
          {state.kind === 'none' ? (
            answer?.model != null && <NoPickNext answer={answer} onRefine={refine} />
          ) : (
            <ul className="h2-picks__chips">
              {picks.map((p) => (
                <li key={p.resourceId}>
                  {loaded.has(p.resourceId) ? (
                    <button
                      type="button"
                      className="h2-pick-chip"
                      title={p.title}
                      onClick={() => select(p.resourceId)}
                    >
                      <b>#{p.rank}</b> <span className="h2-pick-chip__title">{p.title}</span>
                    </button>
                  ) : (
                    <Link className="h2-pick-chip" title={p.title} to={resourcePath(p.resourceId)}>
                      <b>#{p.rank}</b> <span className="h2-pick-chip__title">{p.title}</span>
                    </Link>
                  )}
                </li>
              ))}
            </ul>
          )}
        </PicksBar>
      )}
      {/* While the guide reads, a blank line of the same style holds the place of "web searches left", which comes with
          the answer ([webLine]: the search's count says the line will show). */}
      {busy && error === null
        ? webLine && (
            <p className="muted small h2-web-left" aria-hidden="true">
              &nbsp;
            </p>
          )
        : !busy && <WebSearchesLeft feature="guide" value={answer?.webSearches} />}
      <AiAnswersLeft answer={answer} />
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
      {/* While the guide reads, the row of "How the guide decided" is kept, so the list does not move when it comes. */}
      {busy && error === null && <div className="h2-results__full-slot" aria-hidden="true" />}
      {answered && <FullAnswer answer={answer} onRefine={refine} />}
    </div>
  );
}

/** While busy, the reading parts are plain labels: ✕ only comes with the answer, so a second question never runs alongside. */
function picksState(stream: GuideStream, pickCount: number, t: TFunction): PicksState {
  if (!stream.busy) {
    if (pickCount > 0)
      return { kind: 'picks', model: stream.answer?.model?.reasoningModel ?? null };
    return { kind: 'none', reason: stream.answer ? noPickReason(stream.answer, t) : undefined };
  }
  const { reading, candidates } = progressOf(stream.steps);
  const understood = reading
    ? readingParts(reading, new Date().toISOString().slice(0, 10), t).map((p) => p.label)
    : [];
  return { kind: 'reading', candidates, understood };
}

/**
 * The next steps when the guide found nothing that fits: ask again without one requirement (the same structured
 * removal as ✕ in "Understood as"), or rephrase in the box. The requirements are AI-written: marked on each, labelled
 * once (ADR 0038).
 */
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
          className="btn btn-ghost"
          onClick={() => onRefine({ drop: [p.drop!] })}
        >
          {/* One text run: the button lays out its children with a gap. */}
          <span>
            {t('picks.askWithout')} “<span {...aiMark()}>{p.label}</span>”
          </span>
        </button>
      ))}
      <button type="button" className="btn btn-ghost" onClick={rephrase}>
        {t('picks.rephrase')}
      </button>
      {requirements.length > 0 && <span className="h2-row__ai">{t('guide.requirementsAi')}</span>}
    </div>
  );
}

/** How the guide decided, folded away under the picks bar. */
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
