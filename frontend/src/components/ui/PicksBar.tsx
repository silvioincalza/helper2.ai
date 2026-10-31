import { useId, type ReactNode, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import './ui.css';

/**
 * reading: the guide works ([candidates] once the search is counted, [understood] once it has read the question).
 * none: no pick. picks: the picks are in.
 */
export type PicksState =
  | { kind: 'reading'; candidates?: number | undefined; understood?: readonly string[] | undefined }
  | { kind: 'none' }
  | { kind: 'picks'; model?: string | null };

/**
 * The box above the result list that holds the guide's picks (ADR 0040, 6). It is there from the first render with
 * its height reserved, so nothing below it moves when the answer arrives; with no pick it shrinks to one line.
 * The waiting and no-pick lines are a status, so a screen reader hears each change once: how the question was read,
 * then how many results the guide reads. The picks ([children]) sit under an AI-written label on screen. They are
 * source titles, so they carry no machine-readable mark (ADR 0038): only the guide's reasons do, in the rows. [titleRef] lets Try again move focus to the heading.
 */
export function PicksBar({ state, titleRef, children }: { state: PicksState; titleRef?: Ref<HTMLHeadingElement>; children?: ReactNode }) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <section className={`h2-picks is-${state.kind}`} aria-labelledby={titleId}>
      <h2 id={titleId} ref={titleRef} tabIndex={-1} className="h2-picks__title">{t('picks.title')}</h2>
      {state.kind === 'picks' ? (
        <>
          <span className="h2-picks__ai">{t('picks.aiWritten')}</span>
          <div className="h2-picks__list">{children}</div>
        </>
      ) : (
        <p className="h2-picks__line" role="status">
          {state.kind === 'none'
            ? t('picks.none')
            : state.candidates === undefined ? t('picks.readingResults') : t('picks.reading', { count: state.candidates })}
          {state.kind === 'reading' && (state.understood?.length ?? 0) > 0 && (
            <span className="h2-picks__understood"> · {t('picks.understood', { parts: state.understood!.join(', ') })}</span>
          )}
        </p>
      )}
    </section>
  );
}
