import { useId, useMemo, useRef, useState, type RefObject } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import { sortOrderValues } from '@/api/schema';
import type { SearchResponse } from '@/api/types';
import { ActiveFilters } from '@/components/ActiveFilters';
import { FilterPanel } from '@/components/FilterPanel';
import { GuidePicks } from '@/components/guide/GuidePicks';
import { WebSearchesLeft, webLineExpected } from '@/components/guide/WebSearchesLeft';
import { Pagination } from '@/components/Pagination';
import { ResultsTimeline } from '@/components/ResultsTimeline';
import { NoticeList } from '@/components/ui/NoticeList';
import { SourceTiles } from '@/components/ui/SourceTiles';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useGuideStream } from '@/hooks/useGuideStream';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { aiMark } from '@/lib/aiMark';
import { guidePicks, picksById, type GuidePick } from '@/lib/selection';
import {
  activeFilterCount,
  askedQuestion,
  clampQuestion,
  clearFilters,
  homeParams,
  isQuestion,
  isSelected,
  parseSearchParams,
  patchState,
  toApiParams,
  toggleFilter,
  type ResultsView,
  type SearchState,
} from '@/lib/searchState';
import { missedSources, retryCanHelp } from '@/lib/coverage';
import { AudienceChoice } from './AudienceChoice';
import { CoverageLine } from './CoverageLine';
import { FiltersPanel } from './FiltersPanel';
import { ResultRow } from './ResultRow';
import { ResultsSkeleton } from './ResultsSkeleton';
import { TypeTabs } from './TypeTabs';
import './results.css';

/** Up to this width the filters open as a drawer over the page (design v4 breakpoints). */
const PHONE = '(max-width: 720px)';

/**
 * Results view (ADR 0042, v4-2), loaded on demand under the page's h1 and box (HomePage): the guide's picks bar when a
 * question is asked, then one column of results. A row opens the resource page; Back comes back to the same place.
 * The guide's picks stay where the search put them, each marked with its rank, its reason and its quote, so nothing
 * moves when the answer comes. The sources that answered are at the bottom.
 */
export function Results() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const state = parseSearchParams(sp);
  const asked = askedQuestion(sp);
  const go = (next: SearchState, ask: string) =>
    setSp(homeParams(next, ask, sp), { preventScrollReset: true });
  const update = (next: SearchState) => go(next, asked);
  const ask = (question: string) => {
    const q = clampQuestion(question);
    if (q) go(patchState(state, { q }), q);
  };
  // One guide stream for the page: the bar shows it, the rows read their picks from it.
  const stream = useGuideStream();
  const picks = useMemo(() => guidePicks(stream.answer), [stream.answer]);

  const apiParams = toApiParams(state);
  const key = apiParams.toString();
  const search = useApi<SearchResponse | null>(`search?${key}`, (signal) =>
    state.q ? api.search(apiParams, signal) : Promise.resolve(null),
  );
  const shown = search.loading ? (search.previous ?? undefined) : (search.data ?? undefined);

  return (
    <>
      {asked ? (
        <GuidePicks
          stream={stream}
          loaded={new Set((shown?.results ?? []).map((c) => c.id))}
          webLine={webLineExpected(shown?.webSearches)}
        />
      ) : (
        isQuestion(state.q) && (
          <section className="h2-suggest" aria-label={t('results.suggestLabel')}>
            <p>{t('results.suggestText')}</p>
            <button type="button" className="v4-btn" onClick={() => ask(state.q)}>
              {t('results.suggestButton', { q: state.q })}
            </button>
          </section>
        )
      )}
      {/* The skeleton only on the first load; later the last results stay, dimmed, under one status line. */}
      {search.loading &&
        (shown ? (
          <p role="status" className="h2-results__loading-line">
            {t('results.loading')}
          </p>
        ) : (
          <ResultsSkeleton />
        ))}
      {!search.loading && search.error !== undefined && (
        <StatePanel
          tone="error"
          level={2}
          title={t('results.errorTitle')}
          next={<RetryButton onClick={search.reload} />}
        >
          <p>
            {search.error instanceof ApiError && search.error.problem?.detail
              ? search.error.problem.detail
              : t('results.errorBody')}
          </p>
        </StatePanel>
      )}
      {shown && (
        <div className={search.loading ? 'is-stale' : undefined} aria-busy={search.loading}>
          <ResultsBody
            res={shown}
            state={state}
            update={update}
            retry={search.reload}
            picks={picks}
            ask={asked || !clampQuestion(state.q) ? undefined : () => ask(state.q)}
          />
        </div>
      )}
    </>
  );
}

function ResultsBody({
  res,
  state,
  update,
  retry,
  picks,
  ask,
}: {
  res: SearchResponse;
  state: SearchState;
  update: (s: SearchState) => void;
  retry: () => void;
  picks: readonly GuidePick[];
  /** Asks the guide the same words; absent when the guide is already asked. */
  ask?: (() => void) | undefined;
}) {
  const { t } = useTranslation();
  const byId = useMemo(() => picksById(picks), [picks]);
  const activeType = state.filters.type.length === 1 ? state.filters.type[0]! : null;
  const setType = (type: string | null) =>
    update(patchState(state, { filters: { ...state.filters, type: type ? [type] : [] } }));
  const stale = useMemo(
    () => new Set(res.sources.filter((x) => x.servedFrom === 'STALE_CACHE').map((x) => x.sourceId)),
    [res.sources],
  );
  const useCaseHints = res.interpretation.detectedUseCases.filter(
    (u) => !isSelected(state.filters.useCase, u),
  );
  // After a page change the new list replaces the old one: reading starts again at the heading.
  const heading = useRef<HTMLHeadingElement>(null);
  const toPage = (page: number) => {
    update(patchState(state, { page }));
    heading.current?.focus();
  };
  const hintsId = useId();
  const missed = useMemo(() => missedSources(res.sources), [res.sources]);
  const nothingSearched =
    res.results.length === 0 &&
    (res.coverage.sourcesSucceeded === 0 ||
      res.notices.some((n) => n.code === 'ALL_SOURCES_FAILED'));

  return (
    <>
      <ListHead
        res={res}
        state={state}
        update={update}
        retry={retry}
        heading={heading}
        coverage={!nothingSearched}
      />
      {(res.facets.type?.length ?? 0) > 0 && (
        <TypeTabs
          total={res.total}
          types={res.facets.type ?? []}
          active={activeType}
          onType={setType}
        />
      )}
      {/* One row: who the results are best for, then the use cases the search detected. */}
      <div className="h2-hints-row">
        <AudienceChoice state={state} onChange={update} />
        {useCaseHints.length > 0 && (
          <div className="hints h2-hints" role="group" aria-labelledby={hintsId}>
            <span id={hintsId} className="h2-hints__label">
              {t('results.suggestUseCase')}
            </span>
            {useCaseHints.map((u) => (
              <button
                key={u}
                type="button"
                className="chip"
                onClick={() => update(toggleFilter(state, 'useCase', u))}
              >
                {t(`useCase.${u}`, { defaultValue: u })}
              </button>
            ))}
          </div>
        )}
      </div>
      <ActiveFilters state={state} sources={res.sources} onChange={update} />

      {/* QUERY_EXPANDED is shown by the interpretation line below; other notices are localized by code. */}
      <NoticeList notices={res.notices} exclude={['QUERY_EXPANDED']} />
      <AlsoSearched interpretation={res.interpretation} />
      <WebSearchesLeft feature="search" value={res.webSearches} />

      {nothingSearched ? (
        <StatePanel
          tone="error"
          level={2}
          title={t('results.allFailedTitle')}
          next={
            <>
              {(missed.length === 0 || retryCanHelp(missed)) && <RetryButton onClick={retry} />}
              <Link to="/sources">{t('results.seeSources')}</Link>
            </>
          }
        >
          <p>{t('results.allFailedBody')}</p>
        </StatePanel>
      ) : res.results.length === 0 ? (
        <StatePanel
          tone="notice"
          live
          level={2}
          title={t('results.emptyTitle')}
          next={
            <>
              {activeFilterCount(state) > 0 && (
                <button type="button" className="btn" onClick={() => update(clearFilters(state))}>
                  {t('results.emptyRemoveFilters')}
                </button>
              )}
              {ask && (
                <button type="button" className="btn btn-primary" onClick={ask}>
                  {t('results.askGuide')}
                </button>
              )}
              <Link to="/sources">{t('results.seeSources')}</Link>
            </>
          }
        >
          <p>{t('results.emptyTryOther')}</p>
        </StatePanel>
      ) : state.view === 'timeline' ? (
        <ResultsTimeline cards={res.results} />
      ) : (
        <ul className="plain-list h2-list" aria-label={t('results.title')}>
          {res.results.map((c) => (
            <ResultRow key={c.id} card={c} pick={byId.get(c.id)} stale={stale} />
          ))}
        </ul>
      )}
      {/* Under the list and under the timeline alike: the timeline asks for 50 per page, so it pages too. */}
      {!nothingSearched && res.results.length > 0 && (
        <Pagination page={res.page} size={res.size} total={res.total} onPage={toPage} />
      )}

      <section id="h2-sources" className="h2-results__sources" aria-labelledby="h2-sources-h">
        <h2 id="h2-sources-h" className="h2-results__fan">
          {/* The same two numbers as the coverage line, so the page says one thing. */}
          {t('results.fanOut', {
            ok: res.coverage.sourcesSucceeded,
            total: res.coverage.sourcesQueried,
            count: res.total,
          })}
        </h2>
        {/* Cache ages as of the search, so the tiles say what the search found. */}
        <SourceTiles sources={res.sources} now={Date.parse(res.executedAt)} />
      </section>
    </>
  );
}

/**
 * The list's heading in the terminal style: "All results" over `$ helper2 search · N` (N = `total` from the API), the
 * Filters button, the sort and the List / Timeline switch (links: the view is in the URL). When some sources did not
 * answer, one quiet line says so here (`CoverageLine`), with no live region: it is read with the heading, not on every
 * search. [coverage] is false when nothing was searched: the error panel says it then.
 */
function ListHead({
  res,
  state,
  update,
  retry,
  heading,
  coverage,
}: {
  res: SearchResponse;
  state: SearchState;
  update: (s: SearchState) => void;
  retry: () => void;
  heading: RefObject<HTMLHeadingElement | null>;
  coverage: boolean;
}) {
  const { t } = useTranslation();
  const [sp] = useSearchParams();
  const timeline = state.view === 'timeline';
  const drawer = useMediaQuery(PHONE, false);
  const [open, setOpen] = useState(false);
  const filtersId = useId();
  const toggle = useRef<HTMLButtonElement>(null);
  const on = activeFilterCount(state);
  return (
    <>
      <div className="h2-all">
        <h2 ref={heading} tabIndex={-1} id="all-h" className="h2-all__title">
          {t('results.all')}{' '}
          <span className="h2-all__cmd">
            <span aria-hidden="true">
              <span className="h2-prompt">$</span>{' '}
              {t(timeline ? 'results.commandTimeline' : 'results.command')} ·{' '}
            </span>
            {res.total}
          </span>
        </h2>
        <div className="h2-all__tools">
          <button
            ref={toggle}
            type="button"
            className="v4-btn"
            aria-expanded={open}
            aria-controls={filtersId}
            onClick={() => setOpen((o) => !o)}
          >
            {t('filters.title')}
            {on > 0 && <span className="h2-all__on">{t('results.filtersOn', { count: on })}</span>}
          </button>
          {timeline ? (
            <p className="h2-all__order">{t('timeline.order')}</p>
          ) : (
            <label className="h2-sort">
              {t('sort.label')}
              <select
                value={state.sort}
                onChange={(e) =>
                  update(patchState(state, { sort: e.target.value as SearchState['sort'] }))
                }
              >
                {sortOrderValues.map((s) => (
                  <option key={s} value={s}>
                    {t(`sort.${s}`)}
                  </option>
                ))}
              </select>
            </label>
          )}
          <nav className="h2-view" aria-label={t('timeline.viewLabel')}>
            {(['list', 'timeline'] as ResultsView[]).map((v) => (
              <Link
                key={v}
                to={{
                  search: `?${homeParams(patchState(state, { view: v }), askedQuestion(sp), sp)}`,
                }}
                preventScrollReset
                aria-current={state.view === v ? 'page' : undefined}
              >
                {t(`timeline.view.${v}`)}
              </Link>
            ))}
          </nav>
        </div>
        {coverage && res.coverage.partial && (
          <div className="h2-partial">
            <CoverageLine res={res} retry={retry} afterCount={false} />
          </div>
        )}
      </div>
      <FiltersPanel
        id={filtersId}
        open={open}
        drawer={drawer}
        total={res.total}
        toggle={toggle}
        onClose={() => setOpen(false)}
      >
        <FilterPanel facets={res.facets} state={state} sources={res.sources} onChange={update} />
        {on > 0 && (
          <button type="button" className="v4-btn" onClick={() => update(clearFilters(state))}>
            {t('results.emptyRemoveFilters')}
          </button>
        )}
      </FiltersPanel>
    </>
  );
}

/**
 * "Also searched for": the model's English translation of the query, labelled and marked AI-written (ADR 0038),
 * then the terms from the fixed word list, which alone carry the "not AI" note.
 */
function AlsoSearched({ interpretation }: { interpretation: SearchResponse['interpretation'] }) {
  const { t } = useTranslation();
  const translated = interpretation.translatedQuery;
  const lexicon = interpretation.expandedTerms;
  if (!translated && lexicon.length === 0) return null;
  return (
    <p className="muted small interpretation">
      {t('results.alsoSearchedLabel')}{' '}
      {translated && (
        <>
          <span {...aiMark()}>{translated}</span>{' '}
          <span className="badge">{t('results.translatedNote')}</span>
          {lexicon.length > 0 && ', '}
        </>
      )}
      {lexicon.length > 0 && (
        <>
          {lexicon.join(', ')} <span>({t('results.lexiconNote')})</span>
        </>
      )}
    </p>
  );
}
