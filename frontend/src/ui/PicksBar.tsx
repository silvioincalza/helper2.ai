import { useId, type ReactNode, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import './ui.css';
import { AiLabel } from '@/ui/AiLabel';

export interface ModelActivity {
  phase: 'reasoning' | 'writing';
  step: 'plan' | 'choose' | 'compare' | 'explain' | 'verify';
}

export type PicksState =
  | {
      kind: 'reading';
      candidates?: number | undefined;
      understood?: readonly string[] | undefined;
      activity?: ModelActivity | undefined;
    }
  | { kind: 'none'; reason?: ReactNode }
  | { kind: 'clarify' }
  | { kind: 'picks'; model?: string | null; count?: number };

export function PicksBar({
  state,
  stale = false,
  titleRef,
  children,
}: {
  state: PicksState;
  stale?: boolean;
  titleRef?: Ref<HTMLHeadingElement>;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <section
      className={`h2-picks is-${state.kind}${stale ? ' is-stale' : ''}`}
      aria-labelledby={titleId}
      aria-busy={stale}
    >
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
        {state.kind === 'picks' && <AiLabel className="h2-picks__ai" />}
      </div>
      <p className={state.kind === 'picks' ? 'sr-only' : 'h2-picks__line'} role="status">
        {state.kind === 'picks'
          ? state.count === undefined
            ? t('picks.ready')
            : t('picks.readyCount', { count: state.count })
          : state.kind === 'none'
            ? (state.reason ?? t('picks.none'))
            : state.kind === 'clarify'
              ? t('picks.clarify')
              : state.activity
                ? t(`picks.activity.${state.activity.phase}.${state.activity.step}`)
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
      {state.kind === 'picks' && (
        <>
          <div className="h2-picks__list">{children}</div>
          {state.model && (
            <p className="h2-picks__model">{t('picks.chosenBy', { model: state.model })}</p>
          )}
        </>
      )}
      {state.kind === 'none' && children}
    </section>
  );
}
