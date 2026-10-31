import { Link, useSearchParams } from 'react-router';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import type { GuideResponse } from '@/api/types';
import { Icon } from '@/components/Icon';
import { PicksBar, type PicksState } from '@/components/ui/PicksBar';
import type { GuideStream } from '@/hooks/useGuideStream';
import { guidePicks } from '@/lib/selection';
import { GuideAnswer } from './GuideAnswer';
import { progressOf, readingParts } from './reading';
import { WebSearchesLeft } from './WebSearchesLeft';

/**
 * The picks bar for the question in the URL: reading, then the picks, or one line when there is none. While the guide
 * works, the line says how it read the question and how many results it reads, as the steps arrive. An answer with
 * no AI pick but something to say (an explanation, a comparison, plain recommendations, notices) is shown in full
 * instead of the no-pick line. A pick that is not on this page of results links to its own page.
 */
export function GuidePicks({ stream, loaded }: { stream: GuideStream; loaded: ReadonlySet<string> }) {
  const { t } = useTranslation();
  const { answer, error, busy, refine, retry } = stream;
  const [, setSp] = useSearchParams();
  const picks = guidePicks(answer);
  const select = (id: string) => setSp((prev) => { const next = new URLSearchParams(prev); next.set('id', id); return next; }, { replace: true, preventScrollReset: true });
  const state = picksState(stream, picks.length, t);
  const answered = answer !== null && answer.status !== 'NEEDS_CLARIFICATION';
  const plainAnswer = answered && picks.length === 0 && (answer.recommendations.length > 0 || !!answer.explanation
    || !!answer.comparison || answer.notices.length > 0);
  return (
    <div className="h2-results__guide">
      {error === null && !plainAnswer && (
        <PicksBar state={state}>
          <ul className="h2-picks__chips">
            {picks.map((p) => (
              <li key={p.resourceId}>
                {loaded.has(p.resourceId) ? (
                  <button type="button" className="h2-pick-chip" onClick={() => select(p.resourceId)}>
                    <b>#{p.rank}</b> {p.title}
                  </button>
                ) : (
                  <Link className="h2-pick-chip" to={`/resource/${encodeURIComponent(p.resourceId)}`}>
                    <b>#{p.rank}</b> {p.title}
                  </Link>
                )}
              </li>
            ))}
          </ul>
        </PicksBar>
      )}
      {!busy && <WebSearchesLeft feature="guide" value={answer?.webSearches} />}
      {error !== null && (
        <div className="state-box state-error">
          <p role="alert"><Icon name="warn" /> {guideError(error, t)}</p>
          <button type="button" className="btn btn-primary" onClick={retry}><Icon name="refresh" /> {t('common.retry')}</button>
        </div>
      )}
      {answer && answer.status === 'NEEDS_CLARIFICATION' && <GuideAnswer answer={answer} onRefine={refine} />}
      {/* No pick: the answer opens under the one-line bar, so how the guide read the question can be refined. */}
      {answered && !plainAnswer && <FullAnswer answer={answer} onRefine={refine} open={picks.length === 0} />}
      {plainAnswer && <GuideAnswer answer={answer} onRefine={refine} />}
    </div>
  );
}

/** While busy, the reading parts are plain labels: ✕ only comes with the answer, so a second question never runs alongside. */
function picksState(stream: GuideStream, pickCount: number, t: TFunction): PicksState {
  if (!stream.busy) return pickCount > 0 ? { kind: 'picks', model: stream.answer?.model?.reasoningModel ?? null } : { kind: 'none' };
  const { reading, candidates } = progressOf(stream.steps);
  const understood = reading ? readingParts(reading, new Date().toISOString().slice(0, 10), t).map((p) => p.label) : [];
  return { kind: 'reading', candidates, understood };
}

/** The whole answer (checks, comparison, how it was made), folded away under the picks bar. */
function FullAnswer({ answer, onRefine, open }: { answer: GuideResponse; onRefine: Parameters<typeof GuideAnswer>[0]['onRefine']; open: boolean }) {
  const { t } = useTranslation();
  return (
    <details className="h2-results__full" open={open}>
      <summary>{t('resultsV2.fullAnswer')}</summary>
      <GuideAnswer answer={answer} onRefine={onRefine} />
    </details>
  );
}

function guideError(err: unknown, t: TFunction): string {
  if (!(err instanceof ApiError)) return t('guide.error');
  if (err.stream) return t(`guide.streamFailure.${err.stream}`);
  return err.problem?.detail ?? t('guide.error');
}
