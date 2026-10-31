import { useId, type ReactNode, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import './ui.css';

/**
 * reading: the guide works ([candidates] once its search is counted). none: no pick, with the [reason] when it is not
 * the evidence (no model, daily limit). picks: the picks are in. ask: the guide asks back. error: the request failed.
 */
export type PicksState =
  | { kind: 'reading'; candidates?: number | undefined }
  | { kind: 'none'; reason?: string | undefined }
  | { kind: 'picks' }
  | { kind: 'ask' }
  | { kind: 'error' };

/**
 * The box above the result list that holds the guide's picks (ADR 0040, 6). It is there from the first render with
 * the height of its three rows reserved (head, line or picks, footer), so nothing below it moves when the answer
 * arrives; with no pick it shrinks to one line.
 * The waiting and no-pick lines are a status, so a screen reader hears each once. The picks and the question back
 * ([children]) sit under an AI-written label, and [ai] names the provider and models of the whole answer (ADR 0038).
 * A pick shows a title from its source, so the bar does not mark it; the AI-written text in [children] marks itself. [question] is what the guide was asked, [onClose] closes the answer, and
 * [footer] (how the guide read the question, the answer's details) follows in every state. The heading takes focus
 * from [titleRef] (Try again, see useRetry).
 */
export function PicksBar({ state, question, onClose, closeLabel, ai, footer, titleRef, children }: {
  state: PicksState;
  question?: string;
  onClose?: () => void;
  closeLabel?: string;
  ai?: { provider: string; models: string[] } | null | undefined;
  footer?: ReactNode;
  titleRef?: Ref<HTMLHeadingElement>;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const titleId = useId();
  const aiWritten = state.kind === 'picks' || state.kind === 'ask';
  return (
    <section className={`h2-picks is-${state.kind}`} aria-labelledby={titleId}
      data-ai-provider={ai?.provider} data-ai-models={ai?.models.join(' ')}>
      <div className="h2-picks__head">
        <h2 id={titleId} ref={titleRef} tabIndex={-1} className="h2-picks__title">{t('picks.title')}</h2>
        {question && <q className="h2-picks__question">{question}</q>}
        {aiWritten && <span className="h2-picks__ai">{t('picks.aiWritten')}</span>}
        {onClose && (
          <button type="button" className="btn btn-icon h2-picks__close" onClick={onClose} aria-label={closeLabel}>×</button>
        )}
      </div>
      {state.kind === 'reading' || state.kind === 'none' ? (
        <p className="h2-picks__line" role="status">
          {state.kind === 'none'
            ? state.reason ?? t('picks.none')
            : state.candidates === undefined ? t('picks.readingResults') : t('picks.reading', { count: state.candidates })}
        </p>
      ) : (
        <div className="h2-picks__list">{children}</div>
      )}
      {footer && <div className="h2-picks__foot">{footer}</div>}
    </section>
  );
}
