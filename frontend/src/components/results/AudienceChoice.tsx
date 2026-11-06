import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { audiencePathValues } from '@/api/schema';
import type { AudiencePath } from '@/api/types';
import { patchState, type SearchState } from '@/lib/searchState';

/**
 * "Best for": Anyone, Apps I can use, Build with AI. Sets the audience `path` of the search, which only changes what
 * ranks first; it never hides a result. A path a home example set shows here as chosen. New words drop it again
 * (patchState), so the row then says "Anyone".
 */
export function AudienceChoice({
  state,
  onChange,
}: {
  state: SearchState;
  onChange: (next: SearchState) => void;
}) {
  const { t } = useTranslation();
  const labelId = useId();
  const choices: (AudiencePath | '')[] = ['', ...audiencePathValues];
  return (
    <div className="hints h2-hints" role="group" aria-labelledby={labelId}>
      <span id={labelId} className="h2-hints__label">
        {t('results.path.label')}
      </span>
      {choices.map((path) => (
        <button
          key={path || 'all'}
          type="button"
          className="chip"
          aria-pressed={state.path === path}
          onClick={() => {
            if (state.path !== path) onChange(patchState(state, { path }));
          }}
        >
          {t(`results.path.${path || 'ALL'}`)}
        </button>
      ))}
    </div>
  );
}
