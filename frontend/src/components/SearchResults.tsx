import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { SearchResponse } from '@/api/types';
import { ActiveFilters } from '@/components/ActiveFilters';
import { FilterPanel } from '@/components/FilterPanel';
import { Icon } from '@/components/Icon';
import { OutcomeBadge } from '@/components/Badges';
import { Pagination } from '@/components/Pagination';
import { ResultCardView } from '@/components/ResultCardView';
import { OpenGuideContext } from '@/components/SetupGuide';
import { ResultsTimeline } from '@/components/ResultsTimeline';
import { SourceStatusPanel } from '@/components/SourceStatusPanel';
import { useSources } from '@/context/SourcesContext';
import { useApi } from '@/hooks/useApi';
import { activeFilterCount, clearFilters, isSelected, patchState, toApiParams, toggleFilter, type ResultsView, type SearchState } from '@/lib/searchState';

/** Results for [state] from every source, with filters, source status and paging. Renders nothing without a query. */
export function SearchResults({ state, update }: { state: SearchState; update: (s: SearchState) => void }) {
  const { t } = useTranslation();
  const apiParams = toApiParams(state);
  const key = apiParams.toString();
  const hasQuery = state.q.trim().length > 0;
  const search = useApi<SearchResponse | null>(`search?${key}`, (signal) => (hasQuery ? api.search(apiParams, signal) : Promise.resolve(null)));
  const registry = useSources();
  // One "Guide me" panel open at a time in the list; a new search closes it.
  const [guide, setGuide] = useState<{ key: string; id: string | null }>({ key, id: null });
  const openId = guide.key === key ? guide.id : null;
  const openGuide = useMemo(() => ({ openId, setOpenId: (id: string | null) => setGuide({ key, id }) }), [openId, key]);
  // While a new search loads, the last results stay on the page (dimmed, aria-busy), so the list does not
  // flash and the control the user just used (sort, filter, page) keeps focus. A skeleton only on the first load.
  const shown = search.loading ? (search.previous ?? undefined) : (search.data ?? undefined);

  if (!hasQuery) return null;
  return (
    <div className="search-page">
      <div className="search-layout">
        <FilterPanel facets={shown?.facets} state={state} sources={shown?.sources ?? []} onChange={update} />
        <section aria-label={t('results.title')} className="results">
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
                  {[0, 1, 2].map((i) => <div key={i} className="skeleton-card" aria-hidden="true" />)}
                </>
              )}
            </div>
          )}

          {!search.loading && search.error !== undefined && (
            <div role="alert" className="state-box state-error">
              <h2 className="h-small"><Icon name="warn" /> {t('results.errorTitle')}</h2>
              <p>{search.error instanceof ApiError && search.error.problem?.detail ? search.error.problem.detail : t('results.errorBody')}</p>
              <button type="button" className="btn btn-primary" onClick={search.reload}><Icon name="refresh" /> {t('common.retry')}</button>
            </div>
          )}

          <div className={`results-body${search.loading ? ' is-stale' : ''}`} aria-busy={search.loading}>
            {shown && (
              <OpenGuideContext.Provider value={openGuide}>
                <ResultsBody res={shown} state={state} update={update} retry={search.reload} />
              </OpenGuideContext.Provider>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

function ResultsBody({ res, state, update, retry }: { res: SearchResponse; state: SearchState; update: (s: SearchState) => void; retry: () => void }) {
  const { t } = useTranslation();
  const filtered = activeFilterCount(state) > 0;
  const nothingSearched = res.results.length === 0 && (res.coverage.sourcesSucceeded === 0 || res.notices.some((n) => n.code === 'ALL_SOURCES_FAILED'));
  const useCaseHints = res.interpretation.detectedUseCases.filter((u) => !isSelected(state.filters.useCase, u));
  const heading = useRef<HTMLHeadingElement>(null);
  const toPage = (page: number) => {
    update(patchState(state, { page }));
    // The new page replaces the list under the pager: start reading again from the results heading.
    heading.current?.focus();
  };
  return (
    <>
      {res.coverage.partial && (
        <div role="status" className="banner banner-warn">
          <Icon name="warn" />
          <div>
            <strong>{t('results.partialTitle')}</strong>{' '}
            {t('results.partialBody', { ok: res.coverage.sourcesSucceeded, total: res.coverage.sourcesQueried })}
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
      <SourceStatusPanel response={res} />
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

      <div className="results-head">
        <h2 ref={heading} tabIndex={-1} className="h-small" aria-live="polite">{t('results.count', { count: res.total })}</h2>
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

      {nothingSearched ? (
        <div role="alert" className="state-box state-error">
          <h3 className="h-small"><Icon name="warn" /> {t('results.allFailedTitle')}</h3>
          <p>{t('results.allFailedBody')}</p>
          <ul className="plain-list">
            {res.sources.map((s) => <li key={s.sourceId}>{s.name} <OutcomeBadge outcome={s.outcome} /></li>)}
          </ul>
          <button type="button" className="btn btn-primary" onClick={retry}><Icon name="refresh" /> {t('common.retry')}</button>
        </div>
      ) : res.results.length === 0 ? (
        <div className="state-box state-empty">
          <h3 className="h-small">{t('results.emptyTitle')}</h3>
          <ul>
            {filtered && <li><button type="button" className="btn btn-link" onClick={() => update(clearFilters(state))}>{t('results.emptyRemoveFilters')}</button></li>}
            <li>{t('results.emptyTryOther')}</li>
          </ul>
          <p>{t('results.emptySearched')}</p>
          <ul className="plain-list">
            {res.sources.map((s) => <li key={s.sourceId}>{s.name} <OutcomeBadge outcome={s.outcome} /></li>)}
          </ul>
        </div>
      ) : state.view === 'timeline' ? (
        <ResultsTimeline cards={res.results} sourceStatuses={res.sources} />
      ) : (
        <ul className="plain-list result-list">
          {res.results.map((c) => (
            <li key={c.id}><ResultCardView card={c} sourceStatuses={res.sources} /></li>
          ))}
        </ul>
      )}
      <Pagination page={res.page} size={res.size} total={res.total} onPage={toPage} />
    </>
  );
}
