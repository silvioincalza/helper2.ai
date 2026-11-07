import { useId, type ReactNode, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import './ui.css';

/**
 * reading: the guide works ([candidates] once the search is counted, [understood] once it has read the question).
 * none: no pick, with [reason] when the answer says why (an element when part of it is AI-marked). picks: the picks are in.
 */
export type PicksState =
  | { kind: 'reading'; candidates?: number | undefined; understood?: readonly string[] | undefined }
  | { kind: 'none'; reason?: ReactNode }
  | { kind: 'picks'; model?: string | null; count?: number };

/**
 * The box above the result list that holds the guide's picks (ADR 0028 (part was 0040), 6). It is there from the first render with
 * its height reserved, so nothing below it moves when the answer arrives; with no pick it shrinks to one line.
 * The waiting and no-pick lines are a status, so a screen reader hears each change once: how the question was read,
 * then how many results the guide reads. The picks ([children]) sit under an AI-written label on screen. They are
 * source titles, so they carry no machine-readable mark (ADR 0018): only the guide's reasons do, in the rows. With no
 * pick, [children] are the next steps, after the line. [titleRef] lets Try again move focus to the heading.
 * In every state the bar is a small terminal (v5): a title row with `$ helper2 guide --picks N` (decoration, hidden from
 * screen readers), then the line or the picks, then the model that chose them.
 */
export function PicksBar({
  state,
  titleRef,
  children,
}: {
  state: PicksState;
  titleRef?: Ref<HTMLHeadingElement>;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <section className={`h2-picks is-${state.kind}`} aria-labelledby={titleId}>
      <div className="h2-picks__bar">
        <h2 id={titleId} ref={titleRef} tabIndex={-1} className="h2-picks__title">
          {t('picks.title')}
        </h2>
        <span className="h2-picks__cmd" aria-hidden="true">
          <span className="h2-prompt">$</span>{' '}
          {state.kind === 'picks' && state.count !== undefined
            ? t('picks.commandCount', { count: state.count })
            : t('picks.command')}
        </span>
        {state.kind === 'picks' && <span className="h2-picks__ai">{t('picks.aiWritten')}</span>}
      </div>
      {state.kind === 'picks' ? (
        <>
          <div className="h2-picks__list">{children}</div>
          {state.model && (
            <p className="h2-picks__model">{t('picks.chosenBy', { model: state.model })}</p>
          )}
        </>
      ) : (
        <>
          <p className="h2-picks__line" role="status">
            {state.kind === 'none'
              ? (state.reason ?? t('picks.none'))
              : state.candidates === undefined
                ? t('picks.readingResults')
                : t('picks.reading', { count: state.candidates })}
            {state.kind === 'reading' && (state.understood?.length ?? 0) > 0 && (
              <span className="h2-picks__understood">
                {' '}
                · {t('picks.understood', { parts: state.understood!.join(', ') })}
              </span>
            )}
          </p>
          {state.kind === 'none' && children}
        </>
      )}
    </section>
  );
}
