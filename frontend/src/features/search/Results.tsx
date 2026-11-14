import { upTo } from '@/lib/breakpoints';
import { SponsorSlot } from '@/features/monetisation/SponsorSlot';
import { useCallback, useEffect, useId, useMemo, useRef, useState, type RefObject } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import { sortOrderValues } from '@/api/schema';
import type { SearchResponse } from '@/api/types';
import { ActiveFilters } from '@/features/search/ActiveFilters';
import { FilterFacets } from '@/features/search/FilterFacets';
import { GuidePicks } from '@/features/guide/GuidePicks';
import { WebSearchesLeft, webLineExpected } from '@/features/guide/WebSearchesLeft';
import { ResultsTimeline } from '@/features/search/ResultsTimeline';
import { NoticeList } from '@/ui/NoticeList';
import { SourceTiles } from '@/ui/SourceTiles';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useCategories, type Categories } from '@/hooks/useCategories';
import { useGuideStream } from '@/hooks/useGuideStream';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { aiMark } from '@/lib/aiMark';
import { guidePicks, picksById, type GuidePick } from '@/lib/selection';
import {
  activeFilterCount,
  askedQuestion,
  askedNow,
  clampQuestion,
  clearFilters,
  homeParams,
  isSelected,
  parseSearchParams,
  patchState,
  toApiParams,
  toggleFilter,
  type ResultsView,
  type SearchState,
} from '@/lib/searchState';
import { missedSources, retryCanHelp } from '@/lib/coverage';
import { searchTerms } from '@/lib/searchTerms';
import { AudienceChoice } from './AudienceChoice';
import { ChipRow } from './ChipRow';
import { CoverageLine } from './CoverageLine';
import { FiltersPanel } from './FiltersPanel';
import { useMorePages } from './morePages';
import { ResultKeys } from './ResultKeys';
import { ResultRow } from './ResultRow';
import { ResultsSkeleton } from './ResultsSkeleton';
import { SourceLine } from './SourceLine';
import { TypeTabs } from './TypeTabs';
import './results.css';
import { AiLabel } from '@/ui/AiLabel';

const PHONE = upTo('small');

export function Results() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const state = parseSearchParams(sp);
  const asked = askedQuestion(sp);
  const go = (next: SearchState, ask: string) =>
    setSp(homeParams(next, ask, sp), { preventScrollReset: true });
  const update = (next: SearchState) => go(next, asked);
  const showPage = (page: number) =>
    setSp(homeParams(patchState(state, { page }), asked, sp), {
      replace: true,
      preventScrollReset: true,
    });
  const ask = (question: string) => {
    const q = clampQuestion(question);
    if (q)
      setSp(homeParams(patchState(state, { q }), q, sp), {
        preventScrollReset: true,
        state: askedNow(),
      });
  };
  const stream = useGuideStream();
  const picked = stream.answer ?? stream.previous;
  const picks = useMemo(() => guidePicks(picked), [picked]);

  const apiParams = toApiParams({ ...state, page: 0 });
  const key = apiParams.toString();
  const search = useApi<SearchResponse | null>(`search?${key}`, (signal) =>
    state.q ? api.search(apiParams, signal) : Promise.resolve(null),
  );
  const kept = search.loading || search.error !== undefined;
  const shown = kept ? (search.previous ?? undefined) : (search.data ?? undefined);
  const badFilters =
    search.error instanceof ApiError && search.error.status === 400 && activeFilterCount(state) > 0;
  const failed =
    !search.loading &&
    (search.error !== undefined || (search.data != null && searchedNothing(search.data)));
  const announce = useRef<HTMLParagraphElement>(null);
  const say = useCallback((text: string) => {
    if (announce.current) announce.current.textContent = text;
  }, []);
  const found = search.loading || failed ? 0 : (search.data?.total ?? 0);
  useEffect(() => {
    say(found > 0 ? t('results.found', { count: found }) : '');
  }, [found, key, say, t]);

  return (
    <ResultKeys>
      <p ref={announce} className="sr-only" aria-live="polite" />
      {shown && !failed && <SourceLine res={shown} />}
      {asked && (
        <GuidePicks
          stream={stream}
          anchored={
            new Set(state.view === 'timeline' ? [] : (shown?.results ?? []).map((c) => c.id))
          }
          cards={shown?.results ?? []}
          webLine={webLineExpected(shown?.webSearches)}
        />
      )}
      {!asked && state.q && !failed && <SponsorSlot query={state.q} loading={search.loading} />}
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
          next={
            badFilters ? (
              <button type="button" className="btn" onClick={() => update(clearFilters(state))}>
                {t('results.emptyRemoveFilters')}
              </button>
            ) : (
              <RetryButton onClick={search.reload} />
            )
          }
        >
          <p>
            {search.error instanceof ApiError && search.error.problem?.detail
              ? search.error.problem.detail
              : t('results.errorBody')}
          </p>
        </StatePanel>
      )}
      {shown && (
        <div aria-busy={search.loading}>
          <ResultsBody
            res={shown}
            loading={search.loading}
            state={state}
            update={update}
            showPage={showPage}
            retry={search.reload}
            picks={picks}
            say={say}
            ask={asked || !clampQuestion(state.q) ? undefined : () => ask(state.q)}
          />
        </div>
      )}
    </ResultKeys>
  );
}

function ResultsBody({
  res,
  state,
  update,
  showPage,
  retry,
  picks,
  say,
  ask,
  loading,
}: {
  res: SearchResponse;
  loading: boolean;
  state: SearchState;
  update: (s: SearchState) => void;
  showPage: (page: number) => void;
  retry: () => void;
  picks: readonly GuidePick[];
  say: (text: string) => void;
  ask?: (() => void) | undefined;
}) {
  const { t } = useTranslation();
  const categories = useCategories();
  const byId = useMemo(() => picksById(picks), [picks]);
  const terms = useMemo(() => searchTerms(state.q), [state.q]);
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
  const heading = useRef<HTMLHeadingElement>(null);
  const more = useMorePages(res, toApiParams({ ...state, page: 0 }), state.page);
  const listRef = useRef<HTMLDivElement>(null);
  const [focusPage, setFocusPage] = useState<number | null>(null);
  const firstNew = focusPage === null ? undefined : more.firstOf(focusPage);
  const added = focusPage === null ? 0 : more.countOf(focusPage);
  useEffect(() => {
    if (!firstNew) return;
    listRef.current
      ?.querySelector<HTMLElement>(
        `[data-id="${CSS.escape(firstNew)}"] :is(.h2-row__title, .tl-title)`,
      )
      ?.focus();
    say(t('results.moreLoaded', { count: added }));
  }, [firstNew, added, say, t]);
  const showMore = () => {
    setFocusPage(more.nextPage);
    showPage(more.nextPage);
  };
  const missed = useMemo(() => missedSources(res.sources), [res.sources]);
  const nothingSearched = searchedNothing(res);

  return (
    <>
      <ListHead
        res={res}
        loading={loading}
        state={state}
        update={update}
        retry={retry}
        heading={heading}
        coverage={!nothingSearched}
        categories={categories}
      />
      {(res.facets.type?.length ?? 0) > 0 && (
        <TypeTabs
          total={res.total}
          types={res.facets.type ?? []}
          active={activeType}
          onType={setType}
        />
      )}
      <div className="h2-hints-row">
        <AudienceChoice state={state} onChange={update} />
        {useCaseHints.length > 0 && (
          <ChipRow label={t('results.suggestUseCase')}>
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
          </ChipRow>
        )}
      </div>
      <ActiveFilters
        state={state}
        sources={res.sources}
        categories={categories.byId}
        onChange={update}
      />

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
      ) : (
        <div ref={listRef}>
          {state.view === 'timeline' ? (
            <ResultsTimeline cards={more.cards} />
          ) : (
            <ul className="plain-list h2-list" aria-label={t('results.title')}>
              {more.cards.map((c) => (
                <ResultRow key={c.id} card={c} pick={byId.get(c.id)} stale={stale} terms={terms} />
              ))}
            </ul>
          )}
          {more.loaders}
          {more.failed ? (
            <StatePanel
              tone="error"
              level={3}
              title={t('results.moreFailed')}
              next={<RetryButton onClick={more.failed.reload} />}
            />
          ) : (
            (more.more || more.loadingMore) && (
              <button
                type="button"
                className="btn h2-more"
                aria-busy={more.loadingMore}
                aria-disabled={more.loadingMore}
                onClick={more.loadingMore ? undefined : showMore}
              >
                {t(more.loadingMore ? 'results.loadingMore' : 'results.showMore')}
              </button>
            )
          )}
        </div>
      )}

      <section id="h2-sources" className="h2-results__sources" aria-labelledby="h2-sources-h">
        <h2 id="h2-sources-h" className="h2-results__fan">
          {t('results.fanOut', {
            ok: res.coverage.sourcesSucceeded,
            total: res.coverage.sourcesQueried,
            count: res.total,
          })}
        </h2>
        <SourceTiles sources={res.sources} now={Date.parse(res.executedAt)} />
      </section>
    </>
  );
}

function ListHead({
  res,
  loading,
  state,
  update,
  retry,
  heading,
  coverage,
  categories,
}: {
  res: SearchResponse;
  loading: boolean;
  state: SearchState;
  update: (s: SearchState) => void;
  retry: () => void;
  heading: RefObject<HTMLHeadingElement | null>;
  coverage: boolean;
  categories: Categories;
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
        <div className="h2-all__tools">
          <button
            ref={toggle}
            type="button"
            className="btn"
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
              <span className="h2-sort__label">{t('sort.label')}</span>
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
        loading={loading}
        toggle={toggle}
        onClose={() => setOpen(false)}
      >
        <FilterFacets
          facets={res.facets}
          state={state}
          sources={res.sources}
          categories={categories}
          onChange={update}
        />
        {on > 0 && (
          <button type="button" className="btn" onClick={() => update(clearFilters(state))}>
            {t('results.emptyRemoveFilters')}
          </button>
        )}
      </FiltersPanel>
    </>
  );
}

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
          <span {...aiMark()}>{translated}</span> <AiLabel>{t('results.translatedNote')}</AiLabel>
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

function searchedNothing(res: SearchResponse): boolean {
  return (
    res.results.length === 0 &&
    (res.coverage.sourcesSucceeded === 0 ||
      res.notices.some((n) => n.code === 'ALL_SOURCES_FAILED'))
  );
}
