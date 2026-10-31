import { useId, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { GuideResponse, ResultCard, SearchResponse } from '@/api/types';
import { ActiveFilters } from '@/components/ActiveFilters';
import { FreshnessBadge } from '@/components/Badges';
import { BookmarkButton } from '@/components/BookmarkButton';
import { FilterPanel } from '@/components/FilterPanel';
import { GuideAnswer } from '@/components/GuideAnswer';
import { canGuide } from '@/components/GuideMe';
import { Icon } from '@/components/Icon';
import { OneBox } from '@/components/OneBox';
import { Pagination } from '@/components/Pagination';
import { ResultsTimeline } from '@/components/ResultsTimeline';
import { GuideBody, GuideMeButton, OpenGuideContext, useGuideToggle } from '@/components/SetupGuide';
import { Timeline } from '@/components/Timeline';
import { PicksBar, type PicksState } from '@/components/ui/PicksBar';
import { ProofQuote } from '@/components/ui/ProofQuote';
import { SelectableRow } from '@/components/ui/SelectableRow';
import { SourceTiles } from '@/components/ui/SourceTiles';
import { StatusPill } from '@/components/ui/StatusPill';
import { TypeTag } from '@/components/ui/TypeTag';
import { useApi } from '@/hooks/useApi';
import { useGuideStream, type GuideStream } from '@/hooks/useGuideStream';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useTitle } from '@/hooks/useTitle';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { SAVED_ENABLED } from '@/lib/features';
import { displayHost, guidePicks, picksById, selectedResult, type GuidePick } from '@/lib/selection';
import { activeFilterCount, askedQuestion, clampQuestion, clearFilters, homeParams, isSelected, parseSearchParams, patchState, toApiParams, toggleFilter, type ResultsView, type SearchState } from '@/lib/searchState';
import { useSources } from '@/context/SourcesContext';
import { ListedBy, ResultFacts } from './ResultFacts';
import './results-v2.css';

/** From this width the preview sits next to the list; below it opens inside the list (design system breakpoints). */
const WIDE = '(min-width: 861px)';

/**
 * Design v2 results page (ADR 0040, 3–7): the box, the guide picks bar, type tabs, the result list and a preview of
 * the selected result; the sources that answered at the bottom. The list is search ranking and never re-orders: the
 * guide's picks get a badge and their reason in their own rows. The selected result is `id` in the URL.
 */
export function ResultsV2() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const state = parseSearchParams(sp);
  const asked = askedQuestion(sp);
  useTitle(state.q);
  const go = (next: SearchState, ask: string) => setSp(homeParams(next, ask, sp), { preventScrollReset: true });
  const update = (next: SearchState) => go(next, asked);
  const ask = (question: string) => {
    const q = clampQuestion(question);
    if (q) go(patchState(state, { q }), q);
  };
  const words = state.q.split(/\s+/).filter(Boolean).length;
  // One guide stream for the page: the bar shows it, the rows read their badges from it.
  const stream = useGuideStream();
  const picks = useMemo(() => picksById(guidePicks(stream.answer)), [stream.answer]);

  const apiParams = toApiParams(state);
  const key = apiParams.toString();
  const search = useApi<SearchResponse | null>(`search?${key}`, (signal) => (state.q ? api.search(apiParams, signal) : Promise.resolve(null)));
  const shown = search.loading ? (search.previous ?? undefined) : (search.data ?? undefined);
  const registry = useSources();

  return (
    <div className="h2-results">
      <h1 className="sr-only">{t('resultsV2.heading', { q: state.q })}</h1>
      <div className="h2-results__box"><OneBox /></div>
      {asked ? <GuidePicks stream={stream} loaded={new Set((shown?.results ?? []).map((c) => c.id))} /> : words >= 4 && (
        <button type="button" className="btn suggest-guide" onClick={() => ask(state.q)}>{t('home.suggestGuide', { q: state.q })}</button>
      )}
      {/* Each new search says so; the skeleton only on the first load, later the last results stay, dimmed. */}
      {search.loading && (
        <div role="status" className="h2-results__loading">
          <p>{t('results.loading')}</p>
          {!shown && (<>
          <ul className="plain-list h2-results__querying">
            {(registry.data ?? []).filter((s) => s.enabled).map((s) => (
              <li key={s.id} className="muted small"><Icon name="clock" /> {t('results.queryingSource', { name: s.name })}</li>
            ))}
          </ul>
          <ul className="plain-list h2-skeleton" aria-hidden="true">
            {[0, 1, 2, 3].map((i) => (
              <li key={i} className="h2-skeleton__row">
                <span className="h2-skeleton__line is-short" />
                <span className="h2-skeleton__line is-title" />
                <span className="h2-skeleton__line" />
              </li>
            ))}
          </ul>
          </>)}
        </div>
      )}
      {!search.loading && search.error !== undefined && (
        <div role="alert" className="state-box state-error">
          <h2 className="h-small"><Icon name="warn" /> {t('results.errorTitle')}</h2>
          <p>{search.error instanceof ApiError && search.error.problem?.detail ? search.error.problem.detail : t('results.errorBody')}</p>
          <button type="button" className="btn btn-primary" onClick={search.reload}><Icon name="refresh" /> {t('common.retry')}</button>
        </div>
      )}
      {shown && (
        <div className={search.loading ? 'is-stale' : undefined} aria-busy={search.loading}>
          <ResultsBody res={shown} state={state} update={update} retry={search.reload} picks={picks} ask={asked || !clampQuestion(state.q) ? undefined : () => ask(state.q)} />
        </div>
      )}
    </div>
  );
}

/**
 * The picks bar for the question in the URL: reading, then the picks, or one line when there is none. An answer with
 * no AI pick but something to say (an explanation, a comparison, plain recommendations, notices) is shown in full
 * instead of the no-pick line. A pick that is not on this page of results links to its own page.
 */
function GuidePicks({ stream, loaded }: { stream: GuideStream; loaded: ReadonlySet<string> }) {
  const { t } = useTranslation();
  const { answer, error, busy, refine, retry } = stream;
  const [, setSp] = useSearchParams();
  const picks = guidePicks(answer);
  const select = (id: string) => setSp((prev) => { const next = new URLSearchParams(prev); next.set('id', id); return next; }, { replace: true, preventScrollReset: true });
  const state: PicksState = busy ? { kind: 'reading' } : picks.length > 0 ? { kind: 'picks', model: answer?.model?.reasoningModel ?? null } : { kind: 'none' };
  const answered = answer !== null && answer.status !== 'NEEDS_CLARIFICATION';
  const plainAnswer = answered && picks.length === 0 && (answer.recommendations.length > 0 || !!answer.explanation
    || !!answer.comparison || answer.notices.length > 0);
  return (
    <div className="h2-results__guide">
      {error === null && !plainAnswer && (
        <PicksBar state={state}>
          <ul className="h2-picks__chips">
            {picks.map((p) => (
              <li key={p.resourceId}>
                {loaded.has(p.resourceId) ? (
                  <button type="button" className="h2-pick-chip" onClick={() => select(p.resourceId)}>
                    <b>#{p.rank}</b> {p.title}
                  </button>
                ) : (
                  <Link className="h2-pick-chip" to={`/resource/${encodeURIComponent(p.resourceId)}`}>
                    <b>#{p.rank}</b> {p.title}
                  </Link>
                )}
              </li>
            ))}
          </ul>
        </PicksBar>
      )}
      {error !== null && (
        <div className="state-box state-error">
          <p role="alert"><Icon name="warn" /> {guideError(error, t)}</p>
          <button type="button" className="btn btn-primary" onClick={retry}><Icon name="refresh" /> {t('common.retry')}</button>
        </div>
      )}
      {answer && answer.status === 'NEEDS_CLARIFICATION' && <GuideAnswer answer={answer} onRefine={refine} />}
      {/* No pick: the answer opens under the one-line bar, so how the guide read the question can be refined. */}
      {answered && !plainAnswer && <FullAnswer answer={answer} onRefine={refine} open={picks.length === 0} />}
      {plainAnswer && <GuideAnswer answer={answer} onRefine={refine} />}
    </div>
  );
}

/** The whole answer (checks, comparison, how it was made), folded away under the picks bar. */
function FullAnswer({ answer, onRefine, open }: { answer: GuideResponse; onRefine: Parameters<typeof GuideAnswer>[0]['onRefine']; open: boolean }) {
  const { t } = useTranslation();
  return (
    <details className="h2-results__full" open={open}>
      <summary>{t('resultsV2.fullAnswer')}</summary>
      <GuideAnswer answer={answer} onRefine={onRefine} />
    </details>
  );
}

function guideError(err: unknown, t: TFunction): string {
  if (!(err instanceof ApiError)) return t('guide.error');
  if (err.stream) return t(`guide.streamFailure.${err.stream}`);
  return err.problem?.detail ?? t('guide.error');
}

function ResultsBody({ res, state, update, retry, picks, ask }: {
  res: SearchResponse; state: SearchState; update: (s: SearchState) => void; retry: () => void; picks: ReadonlyMap<string, GuidePick>;
  /** Asks the guide the same words; absent when the guide is already asked. */
  ask?: (() => void) | undefined;
}) {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const wide = useMediaQuery(WIDE);
  const selected = selectedResult(res.results, sp.get('id'));
  const select = (id: string) => setSp((prev) => { const next = new URLSearchParams(prev); next.set('id', id); return next; }, { replace: true, preventScrollReset: true });
  const [guide, setGuide] = useState<string | null>(null);
  const openGuide = useMemo(() => ({ openId: guide, setOpenId: setGuide }), [guide]);
  const typeCounts = res.facets.type ?? [];
  const activeType = state.filters.type.length === 1 ? state.filters.type[0] : null;
  const setType = (type: string | null) => update(patchState(state, { filters: { ...state.filters, type: type ? [type] : [] } }));
  const okSources = res.sources.filter((s) => s.outcome === 'SUCCEEDED').length;
  const stale = useMemo(() => new Set(res.sources.filter((x) => x.servedFrom === 'STALE_CACHE').map((x) => x.sourceId)), [res.sources]);
  const useCaseHints = res.interpretation.detectedUseCases.filter((u) => !isSelected(state.filters.useCase, u));
  // After a page change the new list replaces the old one: reading starts again at the count.
  const count = useRef<HTMLParagraphElement>(null);
  const toPage = (page: number) => {
    update(patchState(state, { page }));
    count.current?.focus();
  };
  const nothingSearched = res.results.length === 0 && (res.coverage.sourcesSucceeded === 0 || res.notices.some((n) => n.code === 'ALL_SOURCES_FAILED'));

  return (
    <OpenGuideContext.Provider value={openGuide}>
      <nav className="h2-tabs" aria-label={t('resultsV2.types')}>
        <button type="button" className="h2-tab" aria-pressed={activeType === null} onClick={() => setType(null)}>
          {t('resultsV2.allTypes')} <span className="h2-tab__n">{res.total}</span>
        </button>
        {typeCounts.map((f) => (
          <button key={f.value} type="button" className="h2-tab" aria-pressed={activeType === f.value} onClick={() => setType(f.value)}>
            {t(`type.${f.value}`, { defaultValue: f.value })} <span className="h2-tab__n">{f.count}</span>
          </button>
        ))}
      </nav>

      <details className="h2-results__filters">
        <summary>{t('filters.title')}</summary>
        <div className="results-head">
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
        <FilterPanel facets={res.facets} state={state} sources={res.sources} onChange={update} />
      </details>
      <ActiveFilters state={state} sources={res.sources} onChange={update} />

      {/* QUERY_EXPANDED is shown by the interpretation line below; other notices are localized by code. */}
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

      {res.coverage.partial && (
        <div role="status" className="banner banner-warn h2-partial">
          <p><Icon name="warn" /> {t('results.partialBody', { ok: res.coverage.sourcesSucceeded, total: res.coverage.sourcesQueried })}</p>
          <p className="h2-partial__next">
            <a href="#h2-sources-h">{t('resultsV2.seeWhich')}</a>
            <button type="button" className="btn" onClick={retry}><Icon name="refresh" /> {t('common.retry')}</button>
          </p>
        </div>
      )}

      {nothingSearched ? (
        <div role="alert" className="state-box state-error">
          <h2 className="h-small"><Icon name="warn" /> {t('results.allFailedTitle')}</h2>
          <p>{t('results.allFailedBody')}</p>
          <p className="h2-state__next">
            <button type="button" className="btn btn-primary" onClick={retry}><Icon name="refresh" /> {t('common.retry')}</button>
            <Link to="/sources">{t('resultsV2.seeSources')}</Link>
          </p>
        </div>
      ) : res.results.length === 0 ? (
        <div role="status" className="state-box state-empty">
          <h2 className="h-small">{t('results.emptyTitle')}</h2>
          <p>{t('results.emptyTryOther')}</p>
          <p className="h2-state__next">
            {activeFilterCount(state) > 0 && (
              <button type="button" className="btn" onClick={() => update(clearFilters(state))}>{t('results.emptyRemoveFilters')}</button>
            )}
            {ask && <button type="button" className="btn btn-primary" onClick={ask}>{t('resultsV2.askGuide')}</button>}
            <Link to="/sources">{t('resultsV2.seeSources')}</Link>
          </p>
        </div>
      ) : state.view === 'timeline' ? (
        <ResultsTimeline cards={res.results} sourceStatuses={res.sources} />
      ) : (
        <div className="h2-split">
          <div className="h2-list">
            <p ref={count} tabIndex={-1} className="h2-list__count" aria-live="polite">{t('results.count', { count: res.total })}</p>
            <ul className="plain-list" aria-label={t('results.title')}>
              {res.results.map((c) => {
                const on = c.id === selected?.id;
                return (
                  <li key={c.id}>
                    <SelectableRow selected={on} onSelect={() => select(c.id)}>
                      <RowContent card={c} pick={picks.get(c.id)} />
                    </SelectableRow>
                    {on && !wide && (
                      <div className="h2-inline-preview">
                        <Preview card={c} pick={picks.get(c.id)} stale={stale} inline />
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          </div>
          {wide && selected && (
            <article className="h2-preview" aria-labelledby="h2-preview-title">
              <Preview card={selected} pick={picks.get(selected.id)} stale={stale} />
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
          {t('resultsV2.fanOut', { ok: okSources, total: res.sources.length, count: res.total })}
        </h2>
        {/* Cache ages as of the search, so the tiles say what the search found. */}
        <SourceTiles sources={res.sources} now={Date.parse(res.executedAt)} />
      </section>
    </OpenGuideContext.Provider>
  );
}

/** One row: type, host, the pick badge, the title and two lines of the source's own text; a pick adds its reason. */
function RowContent({ card, pick }: { card: ResultCard; pick: GuidePick | undefined }) {
  const { t } = useTranslation();
  return (
    <span className="h2-row">
      <span className="h2-row__meta">
        <TypeTag type={card.type} />
        <span className="h2-row__host">{displayHost(card.url)}</span>
        {pick && <StatusPill tone="next">{t('resultsV2.pick', { rank: pick.rank })}</StatusPill>}
      </span>
      <span className="h2-row__title">{card.title}</span>
      {card.description && <span className="h2-row__text">{card.description}</span>}
      {pick && (
        <span className="h2-row__reason">
          <span className="h2-row__ai">{t('picks.aiWritten')}</span> <span {...aiMark()}>{pick.reason}</span>
        </span>
      )}
    </span>
  );
}

/** The selected result: actions, the proof, Use and Build facts, who lists it and its dates. */
function Preview({ card, pick, stale, inline = false }: { card: ResultCard; pick: GuidePick | undefined; stale: ReadonlySet<string>; inline?: boolean }) {
  const { t } = useTranslation();
  const host = displayHost(card.url);
  const guide = useGuideToggle(card.id);
  const bodyId = `guide-${useId()}`;
  const button = useRef<HTMLButtonElement>(null);
  const location = useLocation();
  // The resource page links back to this search and shows the same checked sentence.
  const detail = { pathname: `/resource/${card.id}` };
  const back = new URLSearchParams(location.search);
  back.set('id', card.id);
  const detailState = { from: `${location.pathname}?${back.toString()}`, quote: pick?.quote ?? null };
  // The same levels inline and beside the list: the page's h1, then this h2.
  const TitleTag = 'h2';
  const SecTag = 'h3';
  const GroupTag = 'h4';
  return (
    <div className="h2-pv">
      <div className="h2-pv__top"><TypeTag type={card.type} /><span className="h2-row__host">{host}</span><FreshnessBadge freshness={card.freshness} /></div>
      <TitleTag id={inline ? undefined : 'h2-preview-title'} className="h2-pv__title">
        <Link to={detail} state={detailState}>{card.title}</Link>
      </TitleTag>
      <div className="h2-pv__actions">
        <a className="btn btn-primary" href={card.url} target="_blank" rel="noopener noreferrer">
          {t('resultsV2.openOn', { host })} <span aria-hidden="true">↗</span>
        </a>
        {SAVED_ENABLED && <BookmarkButton card={card} />}
        {canGuide(card) && <GuideMeButton title={card.title} open={guide.open} onToggle={guide.toggle} bodyId={bodyId} buttonRef={button} />}
        {inline && <Link className="btn" to={detail} state={detailState}>{t('resultsV2.fullDetails')}</Link>}
      </div>
      {canGuide(card) && (
        <div id={bodyId} hidden={!guide.open}>{guide.open && <GuideBody id={card.id} title={card.title} level={3} />}</div>
      )}

      <SecTag className="h2-pv__sec">{t('resultsV2.whyMatches')}</SecTag>
      {pick ? (
        <>
          <ProofQuote text={card.description ?? ''} quote={pick.quote} source={host} />
          <p className="h2-pv__reason"><span className="h2-row__ai">{t('picks.aiWritten')}</span> <span {...aiMark()}>{pick.reason}</span></p>
        </>
      ) : card.description ? (
        <p className="h2-pv__text" {...(isAiOrigin(card.descriptionOrigin) ? aiMark() : {})}>{card.description}</p>
      ) : (
        <p className="muted">{t('card.noDescription')}</p>
      )}

      <SecTag className="h2-pv__sec">{t('resultsV2.facts')}</SecTag>
      <ResultFacts card={card} groupTag={GroupTag} />

      <SecTag className="h2-pv__sec">{t('resultsV2.listedBy')}</SecTag>
      <ListedBy card={card} stale={stale} />

      <SecTag className="h2-pv__sec">{t('resultsV2.timeline')}</SecTag>
      <Timeline timeline={card.timeline} compact />
    </div>
  );
}
