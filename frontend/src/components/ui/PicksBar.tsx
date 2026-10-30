import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { aiMark } from '@/lib/aiMark';
import './ui.css';

/** reading: the guide works ([candidates] once the search is counted). none: no pick. picks: the picks are in. */
export type PicksState =
  | { kind: 'reading'; candidates?: number }
  | { kind: 'none' }
  | { kind: 'picks'; model?: string | null };

/**
 * The box above the result list that holds the guide's picks (ADR 0040, 6). It is there from the first render with
 * its height reserved, so nothing below it moves when the answer arrives; with no pick it shrinks to one line.
 * The waiting and no-pick lines are a status, so a screen reader hears each once. The picks ([children]) sit under
 * an AI-written label and are marked machine-readable (ADR 0038).
 */
export function PicksBar({ state, children }: { state: PicksState; children?: ReactNode }) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <section className={`h2-picks is-${state.kind}`} aria-labelledby={titleId}>
      <h2 id={titleId} className="h2-picks__title">{t('picks.title')}</h2>
      {state.kind === 'picks' ? (
        <>
          <span className="h2-picks__ai">{t('picks.aiWritten')}</span>
          <div className="h2-picks__list" {...aiMark(state.model)}>{children}</div>
        </>
      ) : (
        <p className="h2-picks__line" role="status">
          {state.kind === 'none'
            ? t('picks.none')
            : state.candidates === undefined ? t('picks.readingResults') : t('picks.reading', { count: state.candidates })}
        </p>
      )}
    </section>
  );
}
