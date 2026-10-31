import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { ResultCard, SearchResponse } from '@/api/types';
import { ActiveFilters } from '@/components/ActiveFilters';
import { OutcomeBadge } from '@/components/Badges';
import { FilterPanel } from '@/components/FilterPanel';
import { GuidePicks, guidePicks, type GuidePick } from '@/components/GuidePicks';
import { Icon } from '@/components/Icon';
import { Pagination } from '@/components/Pagination';
import { ResultList, rowId } from '@/components/ResultList';
import { InlinePreview, ResultPreview } from '@/components/ResultPreview';
import { SourcesSummary } from '@/components/SourcesSummary';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { useSources } from '@/context/SourcesContext';
import { useApi } from '@/hooks/useApi';
import { useGuideStream, type GuideStream } from '@/hooks/useGuideStream';
import { useNarrow } from '@/hooks/useNarrow';
import { useRetry } from '@/hooks/useRetry';
import { useTitle } from '@/hooks/useTitle';
import {
  activeFilterCount, askedQuestion, clampQuestion, clearFilters, isSelected, parseSearchParams, patchState, RESULTS_PATH,
  resultsParams, selectedId, toApiParams, toggleFilter, toSearchParams, withSelected, type ResultsView, type SearchState,
} from '@/lib/searchState';

/** A search of this many words or more reads like a question: the page offers to ask the guide. */
const QUESTION_WORDS = 4;
/** Skeleton rows while the first answer loads. */
const SKELETON_ROWS = 5;

/**
 * The results page (ADR 0040, 3–8) on `/search`, and on `/?q=…` with `/search` as its canonical URL. Above the list,
 * the guide's picks bar when the URL asks the guide; then the result list with a preview of the selected result
 * (`id` in the URL); the sources under everything. Everything lives in the URL (see OneBox).
 */
export default function ResultsPage() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const state = parseSearchParams(sp);
  const asked = askedQuestion(sp);
  useTitle(state.q || asked);
  const guide = useGuideStream();

  // A filter, sort or page change keeps the reader where they are.
  const go = (next: SearchState, ask: string) => setSp(resultsParams(next, ask, sp), { preventScrollReset: true });
  const ask = (question: string) => {
    const q = clampQuestion(question);
    if (q) go(patchState(state, { q }), q);
  };
  const canonical = new URL(`${RESULTS_PATH}?${toSearchParams(state).toString()}`, window.location.origin).href;
  const words = state.q.split(/\s+/).filter(Boolean).length;

  return (
    <div className="results-page">
      {/* React puts it in the head: search engines see one page for /search and /?q=…. */}
      <link rel="canonical" href={canonical} />
      <h1 className="sr-only">{t('results.heading', { q: state.q || asked })}</h1>
      {!asked && words >= QUESTION_WORDS && (
        <button type="button" className="btn suggest-guide" onClick={() => ask(state.q)}>
          {t('home.suggestGuide', { q: state.q })}
        </button>
      )}
      {(state.q || asked) && <Results state={state} asked={asked} guide={guide} update={(next) => go(next, asked)} onAsk={() => ask(state.q)}
        onCloseGuide={() => go(state, '')} />}
    </div>
  );
}

function Results({ state, asked, guide, update, onAsk, onCloseGuide }: {
  state: SearchState;
  asked: string;
  guide: GuideStream;
  update: (s: SearchState) => void;
  onAsk: () => void;
  onCloseGuide: () => void;
}) {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const apiParams = toApiParams(state);
  const hasQuery = state.q.length > 0;
  const search = useApi<SearchResponse | null>(`search?${apiParams.toString()}`, (signal) => (hasQuery ? api.search(apiParams, signal) : Promise.resolve(null)));
  const registry = useSources();
  const narrow = useNarrow();
  // Try again and a new page both start reading again from the results heading.
  const { retry, focusRef: heading } = useRetry(search.reload, search.loading);
  // While a new search loads, the last results stay on the page (dimmed, aria-busy), so the list does not flash and
  // the control the user just used keeps focus. Skeleton rows only on the first load.
  const shown = (search.loading ? search.previous : search.data) ?? undefined;
  const cards = shown?.results ?? [];
  // The id in the URL is untrusted: it only selects among the results on the page; any other id selects the first.
  const selected = cards.find((c) => c.id === selectedId(sp)) ?? cards[0];
  // Replace, not push: Back leaves the search instead of stepping through rows.
  const select = (id: string) => setSp(withSelected(sp, id), { replace: true, preventScrollReset: true });
  const picks = asked ? guidePicks(guide.answer) : new Map<string, GuidePick>();
  const onPick = (id: string) => {
    select(id);
    document.getElementById(rowId(id))?.focus();
  };
  const closeGuide = () => {
    onCloseGuide();
    // The bar and its close button go away: keep the reader on the results.
    heading.current?.focus();
  };
  const toPage = (page: number) => {
    update(patchState(state, { page }));
    // The new page replaces the list under the pager: start reading again from the results heading.
    heading.current?.focus();
  };

  return (
    <>
      {asked && (
        <GuidePicks guide={guide} picks={picks} listed={new Set(cards.map((c) => c.id))} onPick={onPick} onClose={closeGuide} />
      )}
      {hasQuery && (
      <section aria-labelledby="results-count" className="results">
        <div className="results-toolbar">
          <h2 id="results-count" ref={heading} tabIndex={-1} className="h-small" aria-live="polite">
            {shown ? t('results.count', { count: shown.total }) : t('results.title')}
          </h2>
          <div className="view-switch" role="group" aria-label={t('timeline.viewLabel')}>
            {(['list', 'timeline'] as ResultsView[]).map((v) => (
              <button key={v} type="button" className="chip" aria-pressed={state.view === v} onClick={() => update(patchState(state, { view: v }))}>
                {t(`timeline.view.${v}`)}
              </button>
            ))}
          </div>
          {state.view === 'timeline' ? (
            <p className="muted small">{t('timeline.order')}</p>
          ) : (
            <label className="sort">
              {t('sort.label')}{' '}
              <select value={state.sort} onChange={(e) => update(patchState(state, { sort: e.target.value as SearchState['sort'] }))}>
                <option value="RELEVANCE">{t('sort.RELEVANCE')}</option>
                <option value="NEWEST">{t('sort.NEWEST')}</option>
                <option value="RECENTLY_UPDATED">{t('sort.RECENTLY_UPDATED')}</option>
              </select>
            </label>
          )}
        </div>
        <FilterPanel facets={shown?.facets} state={state} sources={shown?.sources ?? []} onChange={update} />
        <ActiveFilters state={state} sources={shown?.sources ?? []} onChange={update} />

        {search.loading && (
          <div role="status" className="loading">
            <p>{t('results.loading')}</p>
            {!shown && (
              <>
                <ul className="plain-list">
                  {(registry.data ?? []).filter((s) => s.enabled).map((s) => (
                    <li key={s.id} className="muted small"><Icon name="clock" /> {t('results.queryingSource', { name: s.name })}</li>
                  ))}
                </ul>
                {Array.from({ length: SKELETON_ROWS }, (_, i) => <div key={i} className="skeleton-row result-skeleton" aria-hidden="true" />)}
              </>
            )}
          </div>
        )}

        {!search.loading && search.error !== undefined && (
          <StatePanel tone="error" level={3} title={t('results.errorTitle')} next={<RetryButton onClick={retry} />}>
            <p>{search.error instanceof ApiError && search.error.problem?.detail ? search.error.problem.detail : t('results.errorBody')}</p>
          </StatePanel>
        )}

        <div className={`results-body${search.loading ? ' is-stale' : ''}`} aria-busy={search.loading}>
          {shown && (
            <ResultsBody res={shown} state={state} update={update} retry={retry} asked={asked} onAsk={onAsk}>
              <div className={`results-split${narrow ? ' is-narrow' : ''}`}>
                <div className="results-list-col">
                  <ResultList cards={cards} view={state.view} selected={selected?.id} onSelect={select} picks={picks}
                    inline={narrow ? (card: ResultCard) => <InlinePreview card={card} pick={picks.get(card.id)} /> : undefined} />
                  <Pagination page={shown.page} size={shown.size} total={shown.total} onPage={toPage} />
                </div>
                {!narrow && selected && (
                  <ResultPreview key={selected.id} card={selected} pick={picks.get(selected.id)} statuses={shown.sources} />
                )}
              </div>
            </ResultsBody>
          )}
        </div>
      </section>
      )}
      {shown && <SourcesSummary res={shown} guideWebSearches={asked ? guide.answer?.webSearches : undefined} />}
    </>
  );
}

/**
 * Everything around the list for one answer: the partial-results banner, notices, the words added to the search and
 * use-case hints; then the list ([children]), or the state that replaces it: no source answered, or no result.
 * Each state says what to do next.
 */
function ResultsBody({ res, state, update, retry, asked, onAsk, children }: {
  res: SearchResponse;
  state: SearchState;
  update: (s: SearchState) => void;
  retry: () => void;
  asked: string;
  onAsk: () => void;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  const filtered = activeFilterCount(state) > 0;
  const nothingSearched = res.results.length === 0 && (res.coverage.sourcesSucceeded === 0 || res.notices.some((n) => n.code === 'ALL_SOURCES_FAILED'));
  const useCaseHints = res.interpretation.detectedUseCases.filter((u) => !isSelected(state.filters.useCase, u));
  const sourceList = (
    <ul className="plain-list">
      {res.sources.map((s) => <li key={s.sourceId}>{s.name} <OutcomeBadge outcome={s.outcome} /></li>)}
    </ul>
  );
  return (
    <>
      {res.coverage.partial && !nothingSearched && (
        <div role="status" className="banner banner-warn">
          <Icon name="warn" />
          <div>
            <strong>{t('results.partialTitle')}</strong>{' '}
            {t('results.partialBody', { ok: res.coverage.sourcesSucceeded, total: res.coverage.sourcesQueried })}{' '}
            <a href="#sources-h">{t('results.partialSources')}</a>{' · '}
            <button type="button" className="btn btn-link" onClick={retry}>{t('common.retry')}</button>
          </div>
        </div>
      )}
      {/* QUERY_EXPANDED is shown by the localized interpretation line below; other notices are localized by code. */}
      {res.notices.some((n) => n.code !== 'QUERY_EXPANDED') && (
        <ul className="plain-list notices">
          {res.notices.filter((n) => n.code !== 'QUERY_EXPANDED').map((n, i) => (
            <li key={`${n.code}-${i}`} className={`notice notice-${n.code.toLowerCase()}`}>
              <Icon name={n.code === 'ALL_SOURCES_FAILED' ? 'warn' : 'info'} />
              <span className="sr-only">{t(n.code === 'ALL_SOURCES_FAILED' ? 'results.noticeWarning' : 'results.noticeInfo')}: </span>
              {t(`results.notice.${n.code}`, { defaultValue: n.message })}
            </li>
          ))}
        </ul>
      )}
      {res.interpretation.expandedTerms.length > 0 && (
        <p className="muted small interpretation">
          {t('results.alsoSearched', { terms: res.interpretation.expandedTerms.join(', ') })}{' '}
          <span>({t('results.lexiconNote')})</span>
        </p>
      )}
      {useCaseHints.length > 0 && (
        <div className="hints">
          <span className="muted small">{t('results.suggestUseCase')}</span>{' '}
          {useCaseHints.map((u) => (
            <button key={u} type="button" className="chip" onClick={() => update(toggleFilter(state, 'useCase', u))}>
              {t(`useCase.${u}`, { defaultValue: u })}
            </button>
          ))}
        </div>
      )}

      {nothingSearched ? (
        <StatePanel tone="error" level={3} title={t('results.allFailedTitle')} next={<RetryButton onClick={retry} />}>
          <p>{t('results.allFailedBody')}</p>
          {sourceList}
        </StatePanel>
      ) : res.results.length === 0 ? (
        <StatePanel tone="notice" level={3} title={t('results.emptyTitle')} next={(
          <ul className="plain-list h2-state__steps">
            {filtered && <li><button type="button" className="btn btn-link" onClick={() => update(clearFilters(state))}>{t('results.emptyRemoveFilters')}</button></li>}
            <li>{t('results.emptyTryOther')}</li>
            {!asked && <li><button type="button" className="btn btn-link" onClick={onAsk}>{t('results.emptyAsk')}</button></li>}
          </ul>
        )}>
          <p>{t('results.emptySearched')}</p>
          {sourceList}
        </StatePanel>
      ) : children}
    </>
  );
}
