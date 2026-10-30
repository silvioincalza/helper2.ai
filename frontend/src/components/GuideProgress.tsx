import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { GuideStep, GuideStreamChecked, SourceStatus } from '@/api/types';

/** The steps that do the work, one row each. How the question was read and a clarifying question are not rows. */
type StepKind = Exclude<GuideStep['kind'], 'understood' | 'clarify'>;
const ORDER: StepKind[] = ['planned', 'retrieved', 'chosen', 'validated', 'checked'];

type Found = { [K in GuideStep['kind']]?: Extract<GuideStep, { kind: K }>['data'] };

/** The step the guide is working on: the first one with no event yet. A step the server skipped is never shown as running. */
export function currentStep(found: Found): StepKind | null {
  const last = ORDER.reduce((acc, k, i) => (found[k] ? i : acc), -1);
  return ORDER[last + 1] ?? null;
}

function answered(sources: SourceStatus[]) {
  return sources.filter((s) => s.outcome === 'SUCCEEDED').length;
}

/**
 * Live progress of one guide question, from the real events of POST /guide/stream. Each row says what
 * actually happened (search terms, candidates, picks, checks); nothing is timed or guessed on the client.
 */
export function GuideProgress({ steps }: { steps: GuideStep[] }) {
  const { t } = useTranslation();
  const found: Found = {};
  for (const s of steps) (found as Record<string, unknown>)[s.kind] = s.data;
  const now = currentStep(found);
  const seconds = useElapsedSeconds();

  const detail = (k: StepKind): string => {
    switch (k) {
      case 'planned':
        return found.planned ? t('guide.step.plannedDone', { q: found.planned.searchQuery }) : t('guide.step.plannedRun');
      case 'retrieved':
        return found.retrieved
          ? t('guide.step.retrievedDone', { n: found.retrieved.candidates, ok: answered(found.retrieved.sources), all: found.retrieved.sources.length })
          : t('guide.step.retrievedRun');
      case 'chosen':
        return found.chosen ? t('guide.step.chosenDone', { count: found.chosen.proposed }) : t('guide.step.chosenRun');
      case 'validated':
        return found.validated
          ? t('guide.step.validatedDone', { kept: found.validated.kept, rejected: found.validated.rejected })
          : t('guide.step.validatedRun');
      case 'checked':
        return found.checked ? checkedDetail(found.checked) : t('guide.step.checkedRun');
    }
  };
  const checkedDetail = ({ supported, removed, notChecked }: GuideStreamChecked) => {
    const done = t('guide.step.checkedDone', { supported, removed });
    return notChecked > 0 ? `${done} · ${t('guide.step.checkedNotChecked', { count: notChecked })}` : done;
  };
  const model = (k: StepKind) => (k === 'planned' ? found.planned?.model : k === 'chosen' ? found.chosen?.model : undefined);

  return (
    <div className="guide-progress">
      {/* Only the current step's title is live, so a screen reader hears one line per step. The elapsed
          counter changes every second; it sits outside the live region and is hidden from screen readers. */}
      <p className="guide-status">
        <span id="guide-status" role="status">
          <span className="spinner" aria-hidden="true" />
          {t(now ? `guide.step.${now}Title` : 'guide.step.finishing')}
        </span>{' '}
        <span className="muted small" aria-hidden="true">{t('guide.elapsed', { s: seconds })}</span>
      </p>
      <ol className="plain-list progress-steps" aria-label={t('guide.progressLabel')}>
        {ORDER.map((k, i) => {
          const done = found[k] !== undefined;
          const state = done ? 'done' : k === now ? 'active' : 'waiting';
          return (
            <li key={k} className={`progress-step is-${state}`}>
              <span className="progress-mark" aria-hidden="true">{done ? '✓' : i + 1}</span>
              <div className="progress-body">
                <p className="progress-title">
                  {t(`guide.step.${k}Title`)}
                  <span className="sr-only"> — {t(`guide.stepState.${state}`)}</span>
                </p>
                <p className="progress-detail">{detail(k)}</p>
                {k === 'retrieved' && found.retrieved && (
                  <ul className="plain-list source-dots" aria-label={t('guide.sourcesLabel')}>
                    {found.retrieved.sources.map((s) => (
                      <li key={s.sourceId} className={s.outcome === 'SUCCEEDED' ? 'is-ok' : 'is-off'}>
                        {s.name}<span className="sr-only">: {t(`outcome.${s.outcome}`)}</span>
                      </li>
                    ))}
                  </ul>
                )}
                {state === 'active' && <span className="progress-bar" aria-hidden="true" />}
              </div>
              {model(k) && <span className="progress-model">{model(k)}</span>}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/** Whole seconds since mount, so a long wait visibly moves even between events. */
function useElapsedSeconds() {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    const started = Date.now();
    const id = window.setInterval(() => setSeconds(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => window.clearInterval(id);
  }, []);
  return seconds;
}
