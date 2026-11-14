import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { AudiencePath, HomeExamplePool } from '@/api/types';
import { aiMark } from '@/lib/aiMark';
import { AiLabel } from '@/ui/AiLabel';
import {
  draftingModel,
  EXAMPLE_POOL,
  fillsBothBuckets,
  pickExamples,
  POOL_TIMEOUT_MS,
  type Example,
} from '@/lib/examples';
import {
  clampQuery,
  parseSearchParams,
  patchState,
  RESULTS_PATH,
  toSearchParams,
} from '@/lib/searchState';

type Chip =
  | { kind: 'builtIn'; example: Example }
  | {
      kind: 'served';
      id: string;
      label: string;
      query: string;
      path: AudiencePath;
      ai?: { model: string | undefined };
    };

const builtIn = (picked: Example[]): Chip[] =>
  picked.map((example) => ({ kind: 'builtIn', example }));

function served(pool: HomeExamplePool): Chip[] {
  const model = draftingModel(pool);
  return pickExamples(pool.examples).map((e) => ({
    kind: 'served',
    id: e.id,
    label: e.label,
    query: e.query,
    path: e.path,
    ...(e.aiDrafted ? { ai: { model } } : {}),
  }));
}

function loadPool(lang: string, signal: AbortSignal): Promise<HomeExamplePool | null> {
  return new Promise((resolve) => {
    const ctrl = new AbortController();
    const stop = () => {
      clearTimeout(timer);
      ctrl.abort();
      resolve(null);
    };
    const timer = setTimeout(stop, POOL_TIMEOUT_MS);
    signal.addEventListener('abort', stop, { once: true });
    api.listHomeExamples(lang, ctrl.signal).then(
      (pool: HomeExamplePool) => {
        clearTimeout(timer);
        resolve(fillsBothBuckets(pool.examples) ? pool : null);
      },
      () => {
        clearTimeout(timer);
        resolve(null);
      },
    );
  });
}

export function HomeExamples() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const [fallback] = useState(() => builtIn(pickExamples(EXAMPLE_POOL)));
  const [chips, setChips] = useState<Chip[] | null>(null);
  const lang = (i18n.resolvedLanguage ?? 'en').slice(0, 2);

  useEffect(() => {
    const ctrl = new AbortController();
    void loadPool(lang, ctrl.signal).then((pool) => {
      if (!ctrl.signal.aborted) setChips(pool ? served(pool) : fallback);
    });
    return () => ctrl.abort();
  }, [lang, fallback]);

  const text = (chip: Chip) =>
    chip.kind === 'builtIn'
      ? {
          label: t(`home.example.${chip.example.id}.label`),
          query: t(`home.example.${chip.example.id}.query`),
        }
      : { label: chip.label, query: chip.query };
  const key = (chip: Chip) => (chip.kind === 'builtIn' ? chip.example.id : chip.id);
  const path = (chip: Chip) => (chip.kind === 'builtIn' ? chip.example.path : chip.path);

  const search = (chip: Chip) => {
    const q = clampQuery(text(chip).query);
    const state = patchState(parseSearchParams(new URLSearchParams()), { q, path: path(chip) });
    if (q) void navigate({ pathname: RESULTS_PATH, search: toSearchParams(state).toString() });
  };

  return (
    <section aria-labelledby="examples-h" className="home-examples" aria-busy={chips === null}>
      <h2 id="examples-h" className="home-examples__label">
        {t('home.examples')}
      </h2>
      {chips === null ? (
        <ul className="plain-list inline-list home-examples__waiting" aria-hidden="true">
          {fallback.map((chip) => (
            <li key={key(chip)}>
              <span className="chip">{text(chip).label}</span>
            </li>
          ))}
        </ul>
      ) : (
        <ul className="plain-list inline-list">
          {chips.map((chip) => (
            <li key={key(chip)}>
              <button
                type="button"
                className="chip"
                title={chip.kind === 'served' && chip.ai ? t('home.exampleAi') : undefined}
                onClick={() => search(chip)}
              >
                {chip.kind === 'served' && chip.ai ? (
                  <>
                    <span {...aiMark(chip.ai.model)}>{chip.label}</span>{' '}
                    <AiLabel inControl>{t('home.exampleAiMark')}</AiLabel>
                  </>
                ) : (
                  text(chip).label
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
