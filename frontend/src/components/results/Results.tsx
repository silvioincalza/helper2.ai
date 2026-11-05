import {
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type FocusEvent,
  type ReactNode,
  type RefObject,
} from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import { sortOrderValues } from '@/api/schema';
import type { ResultCard, SearchResponse } from '@/api/types';
import { ActiveFilters } from '@/components/ActiveFilters';
import { FreshnessBadge, OriginLabel } from '@/components/Badges';
import { BookmarkButton } from '@/components/BookmarkButton';
import { FilterPanel } from '@/components/FilterPanel';
import { GuidePicks } from '@/components/guide/GuidePicks';
import { WebSearchesLeft, webLineExpected } from '@/components/guide/WebSearchesLeft';
import { canGuide } from '@/components/GuideMe';
import { Icon } from '@/components/Icon';
import { OneBox } from '@/components/OneBox';
import { Pagination } from '@/components/Pagination';
import { ResultsTimeline } from '@/components/ResultsTimeline';
import {
  GuideBody,
  GuideMeButton,
  OpenGuideContext,
  useGuideToggle,
} from '@/components/SetupGuide';
import { Timeline } from '@/components/Timeline';
import { NoticeList } from '@/components/ui/NoticeList';
import { ProofQuote } from '@/components/ui/ProofQuote';
import { RowLink, SelectableRow } from '@/components/ui/SelectableRow';
import { SourceTiles } from '@/components/ui/SourceTiles';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill } from '@/components/ui/StatusPill';
import { TypeTag } from '@/components/ui/TypeTag';
import { useApi } from '@/hooks/useApi';
import { useGuideStream } from '@/hooks/useGuideStream';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useTitle } from '@/hooks/useTitle';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { displayHost } from '@/lib/format';
import { guidePicks, picksById, picksFirst, selectedResult, type GuidePick } from '@/lib/selection';
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
  resourcePath,
} from '@/lib/searchState';
import { AudienceChoice } from './AudienceChoice';
import { ListedBy, ResultFacts } from './ResultFacts';
import './results.css';
import { OpenButton } from './OpenButton';

/**
 * From this width the preview sits next to the list and a click on a row selects it. Below it (phone) nothing is
 * selected on load and a row opens the resource page (design system breakpoints, design v4).
 */
const WIDE = '(min-width: 861px)';

/**
 * Results page (ADR 0040, 3–7): the box, the guide picks bar, type tabs, the result list and a preview of
 * the selected result; the sources that answered at the bottom. When the guide answers, its picks on this page move
 * to a "Guide picks" group at the top of the list, in the guide's order, each with its badge and reason; the other
 * results keep the search order under them. The selected result is `id` in the URL, else the guide's pick #1, else
 * the first row.
 */
export function Results() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const state = parseSearchParams(sp);
  const asked = askedQuestion(sp);
  useTitle(state.q);
  const go = (next: SearchState, ask: string) =>
    setSp(homeParams(next, ask, sp), { preventScrollReset: true });
  const update = (next: SearchState) => go(next, asked);
  const ask = (question: string) => {
    const q = clampQuestion(question);
    if (q) go(patchState(state, { q }), q);
  };
  // One guide stream for the page: the bar shows it, the rows read their badges from it.
  const stream = useGuideStream();
  const picks = useMemo(() => guidePicks(stream.answer), [stream.answer]);

  const apiParams = toApiParams(state);
  const key = apiParams.toString();
  const search = useApi<SearchResponse | null>(`search?${key}`, (signal) =>
    state.q ? api.search(apiParams, signal) : Promise.resolve(null),
  );
  const shown = search.loading ? (search.previous ?? undefined) : (search.data ?? undefined);

  return (
    <div className="h2-results">
      <h1 className="sr-only">{t('results.heading', { q: state.q })}</h1>
      <div className="h2-results__box">
        <OneBox />
      </div>
      {asked ? (
        <GuidePicks
          stream={stream}
          loaded={new Set((shown?.results ?? []).map((c) => c.id))}
          webLine={webLineExpected(shown?.webSearches)}
        />
      ) : (
        isQuestion(state.q) && (
          <button type="button" className="btn suggest-guide" onClick={() => ask(state.q)}>
            {t('home.suggestGuide', { q: state.q })}
          </button>
        )
      )}
      {/* Each new search says so; the skeleton only on the first load, later the last results stay, dimmed. */}
      {search.loading && (
        <div role="status" className="h2-results__loading">
          <p>{t('results.loading')}</p>
          {/* No per-source lines: they arrived after the skeleton and pushed it down (layout shift). */}
          {!shown && (
            <>
              <ul className="plain-list h2-skeleton" aria-hidden="true">
                {[0, 1, 2, 3].map((i) => (
                  <li key={i} className="h2-skeleton__row">
                    <span className="h2-skeleton__line is-short" />
                    <span className="h2-skeleton__line is-title" />
                    <span className="h2-skeleton__line" />
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
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
    </div>
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
  const [sp, setSp] = useSearchParams();
  const wide = useMediaQuery(WIDE);
  const byId = useMemo(() => picksById(picks), [picks]);
  const { picked, rest } = useMemo(() => picksFirst(res.results, picks), [res.results, picks]);
  // An id the user chose (a click, a shared link, a reload) wins; otherwise the guide's pick #1 opens in the preview.
  // On a phone only a chosen id opens: there a row is a link to the resource page.
  const selected = wide
    ? selectedResult(res.results, sp.get('id'), picked[0]?.id)
    : res.results.find((r) => r.id === sp.get('id'));
  const select = (id: string) =>
    setSp(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set('id', id);
        return next;
      },
      { replace: true, preventScrollReset: true },
    );
  const [guide, setGuide] = useState<string | null>(null);
  const openGuide = useMemo(() => ({ openId: guide, setOpenId: setGuide }), [guide]);
  const typeCounts = res.facets.type ?? [];
  const activeType = state.filters.type.length === 1 ? state.filters.type[0] : null;
  const setType = (type: string | null) =>
    update(patchState(state, { filters: { ...state.filters, type: type ? [type] : [] } }));
  const stale = useMemo(
    () => new Set(res.sources.filter((x) => x.servedFrom === 'STALE_CACHE').map((x) => x.sourceId)),
    [res.sources],
  );
  const useCaseHints = res.interpretation.detectedUseCases.filter(
    (u) => !isSelected(state.filters.useCase, u),
  );
  // After a page change the new list replaces the old one: reading starts again at the count.
  const count = useRef<HTMLParagraphElement>(null);
  const toPage = (page: number) => {
    update(patchState(state, { page }));
    count.current?.focus();
  };
  const list = useRef<HTMLDivElement>(null);
  const keepFocus = useKeepRowFocus(list, [...picked, ...rest].map((c) => c.id).join(' '));
  const filtersId = useId();
  const hintsId = useId();
  const [filtersOpen, setFiltersOpen] = useState(false);
  const pickedId = useId();
  const restId = useId();
  const nothingSearched =
    res.results.length === 0 &&
    (res.coverage.sourcesSucceeded === 0 ||
      res.notices.some((n) => n.code === 'ALL_SOURCES_FAILED'));

  return (
    <OpenGuideContext.Provider value={openGuide}>
      <nav className="h2-tabs" aria-label={t('results.types')}>
        <button
          type="button"
          className="h2-tab"
          aria-pressed={activeType === null}
          onClick={() => setType(null)}
        >
          {t('results.allTypes')} <span className="h2-tab__n">{res.total}</span>
        </button>
        {typeCounts.map((f) => (
          <button
            key={f.value}
            type="button"
            className="h2-tab"
            aria-pressed={activeType === f.value}
            onClick={() => setType(f.value)}
          >
            {t(`type.${f.value}`, { defaultValue: f.value })}{' '}
            <span className="h2-tab__n">{f.count}</span>
          </button>
        ))}
      </nav>

      {/* One row: the Filters toggle, who the results are best for, and the use cases the search detected. */}
      <div className="h2-results__tools">
        <button
          type="button"
          className="h2-fold-toggle"
          aria-expanded={filtersOpen}
          aria-controls={filtersId}
          onClick={() => setFiltersOpen((o) => !o)}
        >
          {t('filters.title')} <Icon name="chevron" />
        </button>
        <AudienceChoice state={state} onChange={update} />
        {useCaseHints.length > 0 && (
          <div className="hints" role="group" aria-labelledby={hintsId}>
            <span id={hintsId} className="muted small">
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
      <div id={filtersId} className="h2-results__filters" hidden={!filtersOpen}>
        <div className="results-head">
          <div className="view-switch" role="group" aria-label={t('timeline.viewLabel')}>
            {(['list', 'timeline'] as ResultsView[]).map((v) => (
              <button
                key={v}
                type="button"
                className="chip"
                aria-pressed={state.view === v}
                onClick={() => update(patchState(state, { view: v }))}
              >
                {t(`timeline.view.${v}`)}
              </button>
            ))}
          </div>
          {state.view === 'timeline' ? (
            <p className="muted small">{t('timeline.order')}</p>
          ) : (
            <label className="sort">
              {t('sort.label')}{' '}
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
        <FilterPanel facets={res.facets} state={state} sources={res.sources} onChange={update} />
      </div>
      <ActiveFilters state={state} sources={res.sources} onChange={update} />

      {/* QUERY_EXPANDED is shown by the interpretation line below; other notices are localized by code. */}
      <NoticeList notices={res.notices} exclude={['QUERY_EXPANDED']} />
      <AlsoSearched interpretation={res.interpretation} />

      <WebSearchesLeft feature="search" value={res.webSearches} />

      {res.coverage.partial && (
        <div role="status" className="banner banner-warn h2-partial">
          <p>
            <Icon name="warn" />{' '}
            {t('results.partialBody', {
              ok: res.coverage.sourcesSucceeded,
              total: res.coverage.sourcesQueried,
            })}
          </p>
          <p className="h2-partial__next">
            <a href="#h2-sources-h">{t('results.seeWhich')}</a>
            <button type="button" className="btn" onClick={retry}>
              <Icon name="refresh" /> {t('common.retry')}
            </button>
          </p>
        </div>
      )}

      {nothingSearched ? (
        <StatePanel
          tone="error"
          level={2}
          title={t('results.allFailedTitle')}
          next={
            <>
              <RetryButton onClick={retry} />
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
        <div className="h2-split">
          <div className="h2-list" ref={list} onFocus={keepFocus.onFocus} onBlur={keepFocus.onBlur}>
            <p ref={count} tabIndex={-1} className="h2-list__count" aria-live="polite">
              {t('results.count', { count: res.total })}
            </p>
            {/* Here from the first render, so the move of the picks to the top is said once, politely. */}
            <p className="sr-only" role="status">
              {picked.length > 0 ? t('results.picksOnTop', { count: picked.length }) : ''}
            </p>
            {picked.length > 0 && (
              <>
                <p id={pickedId} className="h2-list__group">
                  {t('results.picksGroup')}
                </p>
                <ul className="plain-list" aria-labelledby={pickedId}>
                  {picked.map((c) => (
                    <ResultRow
                      key={c.id}
                      card={c}
                      pick={byId.get(c.id)}
                      selected={c.id === selected?.id}
                      onSelect={wide ? select : undefined}
                      inline={!wide}
                      stale={stale}
                    />
                  ))}
                </ul>
                <p id={restId} className="h2-list__group">
                  {t('results.moreResults')}
                </p>
              </>
            )}
            <ul
              className="plain-list"
              {...(picked.length > 0
                ? { 'aria-labelledby': restId }
                : { 'aria-label': t('results.title') })}
            >
              {rest.map((c) => (
                <ResultRow
                  key={c.id}
                  card={c}
                  pick={undefined}
                  selected={c.id === selected?.id}
                  onSelect={wide ? select : undefined}
                  inline={!wide}
                  stale={stale}
                />
              ))}
            </ul>
          </div>
          {wide && selected && (
            <article className="h2-preview" aria-labelledby="h2-preview-title">
              <Preview card={selected} pick={byId.get(selected.id)} stale={stale} />
            </article>
          )}
        </div>
      )}
      {/* Under the list and under the timeline alike: the timeline asks for 50 per page, so it pages too. */}
      {!nothingSearched && res.results.length > 0 && (
        <Pagination page={res.page} size={res.size} total={res.total} onPage={toPage} />
      )}

      <section className="h2-results__sources" aria-labelledby="h2-sources-h">
        <h2 id="h2-sources-h" className="h2-results__fan">
          {/* The same two numbers as the partial banner, so the page says one thing. */}
          {t('results.fanOut', {
            ok: res.coverage.sourcesSucceeded,
            total: res.coverage.sourcesQueried,
            count: res.total,
          })}
        </h2>
        {/* Cache ages as of the search, so the tiles say what the search found. */}
        <SourceTiles sources={res.sources} now={Date.parse(res.executedAt)} />
      </section>
    </OpenGuideContext.Provider>
  );
}

/**
 * When the list changes order (the guide's picks move to the top), React moves or remounts the rows and the focused
 * row can lose focus. Focus goes back to the row that had it, without scrolling, so a keyboard user keeps their place.
 */
function useKeepRowFocus(list: RefObject<HTMLDivElement | null>, order: string) {
  const focused = useRef<string | null>(null);
  // React focus handlers on the list element: they work however late the list mounts, and after a view switch.
  const onFocus = (e: FocusEvent<HTMLDivElement>) => {
    focused.current =
      (e.target as HTMLElement).closest('li[data-id]')?.getAttribute('data-id') ?? null;
  };
  const onBlur = (e: FocusEvent<HTMLDivElement>) => {
    if (e.relatedTarget instanceof Node && !e.currentTarget.contains(e.relatedTarget))
      focused.current = null;
  };
  useLayoutEffect(() => {
    const id = focused.current;
    const active = document.activeElement;
    if (!id || (active && active !== document.body)) return;
    list.current
      ?.querySelector<HTMLElement>(`li[data-id="${CSS.escape(id)}"] .h2-select-row__link`)
      ?.focus({ preventScroll: true });
  }, [list, order]);
  return { onFocus, onBlur };
}

/**
 * A result in the list: its row, whose title links to the resource page, and under it the preview when the preview
 * opens inside the list (narrow screens). With [onSelect] (wide screens) a plain click selects the row instead.
 */
function ResultRow({
  card,
  pick,
  selected,
  onSelect,
  inline,
  stale,
}: {
  card: ResultCard;
  pick: GuidePick | undefined;
  selected: boolean;
  onSelect: ((id: string) => void) | undefined;
  inline: boolean;
  stale: ReadonlySet<string>;
}) {
  const location = useLocation();
  // The resource page links back to this search with this result open (on a phone, its preview in the list), and
  // shows the same checked sentence.
  const back = new URLSearchParams(location.search);
  back.set('id', card.id);
  const detailState = {
    from: `${location.pathname}?${back.toString()}`,
    quote: pick?.quote ?? null,
  };
  return (
    <li data-id={card.id}>
      <SelectableRow selected={selected}>
        <RowContent
          card={card}
          pick={pick}
          title={
            <RowLink
              to={resourcePath(card.id)}
              state={detailState}
              selected={selected}
              onSelect={onSelect && (() => onSelect(card.id))}
              className="h2-row__title"
            >
              {card.title}
            </RowLink>
          }
        />
      </SelectableRow>
      {selected && inline && (
        <div className="h2-inline-preview">
          <Preview card={card} pick={pick} stale={stale} inline />
        </div>
      )}
    </li>
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

/** A description a model wrote: labelled and marked on its own (ADR 0038). */
function AiDescription({ card }: { card: ResultCard }) {
  return (
    <p className="h2-pv__text">
      <OriginLabel origin={card.descriptionOrigin} /> <span {...aiMark()}>{card.description}</span>
    </p>
  );
}

/** One row: type, host, the pick badge, the title and two lines of the source's own text; a pick adds its reason. */
function RowContent({
  card,
  pick,
  title,
}: {
  card: ResultCard;
  pick: GuidePick | undefined;
  title: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <span className="h2-row">
      <span className="h2-row__meta">
        <TypeTag type={card.type} />
        <span className="h2-row__host">{displayHost(card.url)}</span>
        {pick && <StatusPill tone="next">{t('results.pick', { rank: pick.rank })}</StatusPill>}
      </span>
      {title}
      {card.description &&
        (isAiOrigin(card.descriptionOrigin) ? (
          <span className="h2-row__text">
            <span className="h2-row__ai">
              <OriginLabel origin={card.descriptionOrigin} />
            </span>{' '}
            <span {...aiMark()}>{card.description}</span>
          </span>
        ) : (
          <span className="h2-row__text">{card.description}</span>
        ))}
      {/* The AI-written label only ever comes with the text it labels. */}
      {pick?.reason && (
        <span className="h2-row__reason">
          <span className="h2-row__ai">{t('picks.aiWritten')}</span>{' '}
          <span {...aiMark()}>{pick.reason}</span>
        </span>
      )}
    </span>
  );
}

/** The selected result: actions, the proof, Use and Build facts, who lists it and its dates. */
function Preview({
  card,
  pick,
  stale,
  inline = false,
}: {
  card: ResultCard;
  pick: GuidePick | undefined;
  stale: ReadonlySet<string>;
  inline?: boolean;
}) {
  const { t } = useTranslation();
  const host = displayHost(card.url);
  const aiDescription = !!card.description && isAiOrigin(card.descriptionOrigin);
  const guide = useGuideToggle(card.id);
  const bodyId = `guide-${useId()}`;
  const location = useLocation();
  // The resource page links back to this search and shows the same checked sentence.
  const detail = { pathname: resourcePath(card.id) };
  const back = new URLSearchParams(location.search);
  back.set('id', card.id);
  const detailState = {
    from: `${location.pathname}?${back.toString()}`,
    quote: pick?.quote ?? null,
  };
  // The same levels inline and beside the list: the page's h1, then this h2.
  const TitleTag = 'h2';
  const SecTag = 'h3';
  const GroupTag = 'h4';
  return (
    <div className="h2-pv">
      <div className="h2-pv__top">
        <TypeTag type={card.type} />
        <span className="h2-row__host">{host}</span>
        <FreshnessBadge freshness={card.freshness} />
      </div>
      <TitleTag id={inline ? undefined : 'h2-preview-title'} className="h2-pv__title">
        <Link to={detail} state={detailState}>
          {card.title}
        </Link>
      </TitleTag>
      <div className="h2-pv__actions">
        <OpenButton url={card.url} host={host} />
        <BookmarkButton card={card} />
        {canGuide(card) && (
          <GuideMeButton
            title={card.title}
            open={guide.open}
            onToggle={guide.toggle}
            bodyId={bodyId}
          />
        )}
        {inline && (
          <Link className="btn" to={detail} state={detailState}>
            {t('results.fullDetails')}
          </Link>
        )}
      </div>
      {canGuide(card) && (
        <div id={bodyId} hidden={!guide.open}>
          {guide.open && <GuideBody id={card.id} title={card.title} level={3} />}
        </div>
      )}

      {pick ? (
        <>
          <SecTag className="h2-pv__sec">{t('results.whyPicked')}</SecTag>
          <PickReason pick={pick} />
          {/* The proof is the source's words: a quote from an AI-written description is not shown as one. */}
          {aiDescription ? (
            <AiDescription card={card} />
          ) : (
            <>
              <p className="h2-pv__proof-label">{t('guide.evidence')}</p>
              <ProofQuote text={card.description ?? ''} quote={pick.quote} source={host} />
            </>
          )}
          <PickChecks checks={pick.checks} />
        </>
      ) : (
        <>
          <SecTag className="h2-pv__sec">{t('results.about')}</SecTag>
          {aiDescription ? (
            <AiDescription card={card} />
          ) : card.description ? (
            <p className="h2-pv__text">{card.description}</p>
          ) : (
            <p className="muted">{t('card.noDescription')}</p>
          )}
        </>
      )}

      <SecTag className="h2-pv__sec">{t('results.facts')}</SecTag>
      <ResultFacts card={card} groupTag={GroupTag} />

      <SecTag className="h2-pv__sec">{t('results.listedBy')}</SecTag>
      <ListedBy card={card} stale={stale} />

      <SecTag className="h2-pv__sec">{t('results.timeline')}</SecTag>
      <Timeline timeline={card.timeline} compact />
    </div>
  );
}

/**
 * The guide's reason for a pick, AI-written: labelled on screen and marked in the HTML (ADR 0038), with what the
 * second model call made of it. A reason that call removed is not shown; the line says why.
 */
function PickReason({ pick }: { pick: GuidePick }) {
  const { t } = useTranslation();
  if (pick.reasonCheck === 'REMOVED' || !pick.reason) {
    return (
      <p className="h2-pv__check">
        {t(pick.reasonCheck === 'REMOVED' ? 'guide.reasonRemoved' : 'results.noReason')}
      </p>
    );
  }
  return (
    <>
      <p className="h2-pv__reason">
        <span className="h2-row__ai">{t('picks.aiWritten')}</span>{' '}
        <span {...aiMark()}>{pick.reason}</span>
      </p>
      <p className="h2-pv__check">
        {t(pick.reasonCheck === 'SUPPORTED' ? 'results.reasonChecked' : 'results.reasonNotChecked')}
      </p>
    </>
  );
}

/** The user's requirements, checked against the source: confirmed with the source's words, or not confirmed. */
function PickChecks({ checks }: { checks: GuidePick['checks'] }) {
  const { t } = useTranslation();
  if (checks.length === 0) return null;
  return (
    <>
      {/* Art. 50: the planner wrote the requirements; labelled on screen once, marked on each. */}
      <p className="h2-pv__proof-label">
        {t('guide.checksLabel')} <span className="h2-row__ai">{t('guide.requirementsAi')}</span>
      </p>
      <ul className="plain-list h2-pv__checks" aria-label={t('guide.checksLabel')}>
        {checks.map((c) => (
          <li
            key={c.constraint}
            className={c.status === 'CONFIRMED' ? 'check-ok' : 'check-unknown'}
          >
            {/* Status in words, never colour alone (WCAG 1.4.1). */}
            <Icon name={c.status === 'CONFIRMED' ? 'check' : 'info'} />{' '}
            <strong {...aiMark()}>{c.constraint}</strong>:{' '}
            {c.status === 'CONFIRMED' ? (
              <>
                {t('guide.checkConfirmed')} <q>{c.quote}</q>
              </>
            ) : (
              t('guide.checkNotConfirmed')
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
