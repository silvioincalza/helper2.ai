import { useTranslation } from 'react-i18next';
import { audiencePathValues } from '@/api/schema';
import type { AudiencePath } from '@/api/types';
import { patchState, type SearchState } from '@/lib/searchState';
import { ChipRow } from './ChipRow';

export function AudienceChoice({
  state,
  onChange,
}: {
  state: SearchState;
  onChange: (next: SearchState) => void;
}) {
  const { t } = useTranslation();
  const choices: (AudiencePath | '')[] = ['', ...audiencePathValues];
  return (
    <ChipRow label={t('results.path.label')}>
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
    </ChipRow>
  );
}
