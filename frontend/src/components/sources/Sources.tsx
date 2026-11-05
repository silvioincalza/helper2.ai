import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { sourceModeValues } from '@/api/schema';
import type { SourceInfo, SourceMode } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { TermCommand } from '@/components/home/TermList';
import { toneOf, type DotTone } from '@/components/home/radarLayout';
import { bounds } from '@/components/home/radarRotation';
import { ScrollBox } from '@/components/ui/ScrollBox';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { api } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useRefreshOnReturn } from '@/hooks/useRefreshOnReturn';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { clockTime, creditsOf, responseTimeShown } from '@/lib/sourceHealth';
import { newThisWeek, radarSources } from '@/lib/v4Fields';
import { ResponseTimeValue, SourceNotes, WebCreditsValue } from './SourceHealth';
import '@/components/ui/term.css';
import './sources.css';

const STEPS = ['ask', 'merge', 'prove', 'dates'] as const;

/** The last check in the Home radar's colours; a disabled source is grey too. */
function stateOf(s: SourceInfo): DotTone {
  return s.enabled ? toneOf(s) : 'idle';
}

/** The glyph before a status word: it repeats the colour for who cannot see it, the word carries the meaning. */
const GLYPH: Record<DotTone, string> = { answering: '✓', warn: '!', failed: '×', idle: '○' };

/**
 * Sources page (ADR 0040, design v4 in ADR 0042): how a search works, then every source as one table in the terminal
 * style, `$ helper2 sources --all · N` with N from GET /sources. Same short names and status colours as the Home
 * radar; the table also lists helper2's own sources, which the radar leaves out. Each row: the source, its last check
 * in words, the mode, what it covers, when it was checked and how fast, new this week, its terms and attribution. A
 * paused source and the metered source's web credits say so under the name. The summary counts the last outcome
 * only: a paused source is not counted as limited.
 */
export function Sources() {
  const { t, i18n } = useTranslation();
  // When the list on screen arrived, for the line after a failed refresh. A list from the recent-answers cache counts
  // from when the page opened.
  const [listedAt, setListedAt] = useState(() => new Date().toISOString());
  const q = useApi('sources', (signal) =>
    api.listSources(signal).then((list) => {
      setListedAt(new Date().toISOString());
      return list;
    }),
  );
  const { retry, focusRef } = useRetry(q.reload, q.loading);
  // Back on the tab after a minute or more: ask again, and keep showing the list meanwhile.
  useRefreshOnReturn(q.reload);
  const list = q.data ?? q.previous;
  const [mode, setMode] = useState<SourceMode | null>(null);
  const all = list ?? [];
  const modes = sourceModeValues.filter((m) => all.some((s) => s.mode === m));
  const shown = mode ? all.filter((s) => s.mode === mode) : all;
  const count = (st: DotTone) => all.filter((s) => stateOf(s) === st).length;
  const onRadar = radarSources(all).length;
  const { fewest, most } = bounds(onRadar);
  return (
    <div className="v4 v4-term h2-sources">
      <header className="v4-term__head">
        <h1>{t('sourcesPage.title')}</h1>
        <p className="v4-term__lede">{t('sourcesPage.lede')}</p>
      </header>

      <section aria-labelledby="how-h">
        <h2 id="how-h" className="v4-term__sub">
          {t('sources.how')}
        </h2>
        <ol className="h2-sources__steps">
          {STEPS.map((k, i) => (
            <li key={k} className="h2-sources__step">
              <span className="h2-sources__n">{i + 1}</span>
              <h3>{t(`sources.steps.${k}.title`)}</h3>
              <p>{t(`sources.steps.${k}.text`)}</p>
            </li>
          ))}
        </ol>
      </section>

      <section aria-labelledby="all-h" className="h2-sources__all">
        <div className="v4-term__bar">
          <h2 id="all-h" ref={focusRef} tabIndex={-1}>
            {t('sources.all')}
          </h2>
          <TermCommand command="helper2 sources --all" total={list ? all.length : null} />
        </div>
        {q.loading && !list && (
          <p role="status" className="v4-term__state">
            <span aria-hidden="true">$ helper2 sources --all · </span>
            {t('common.loading')}
          </p>
        )}
        {!q.loading && q.error !== undefined && !list && (
          <StatePanel
            tone="error"
            level={3}
            title={t('sourcesPage.error')}
            next={<RetryButton onClick={retry} />}
          />
        )}
        {list && (
          <>
            {!q.loading && q.error !== undefined && (
              <p className="h2-sources__stale" role="status">
                {t('sourcesPage.refreshFailed', {
                  time: clockTime(listedAt, i18n.language, false),
                })}
              </p>
            )}
            <p className="h2-sources__summary">
              <span>{t('sources.summary', { ok: count('answering'), total: all.length })}</span>
              {count('warn') > 0 && (
                <span className="v4-status v4-status--warn">
                  <span aria-hidden="true">{GLYPH.warn}</span>
                  {t('sources.warn', { count: count('warn') })}
                </span>
              )}
              {count('failed') > 0 && (
                <span className="v4-status v4-status--failed">
                  <span aria-hidden="true">{GLYPH.failed}</span>
                  {t('sources.failed', { count: count('failed') })}
                </span>
              )}
              {count('idle') > 0 && (
                <span className="v4-status v4-status--idle">
                  <span aria-hidden="true">{GLYPH.idle}</span>
                  {t('sources.idle', { count: count('idle') })}
                </span>
              )}
            </p>
            {onRadar > 0 && (
              <p className="h2-sources__radar">
                {t('sourcesPage.radar', {
                  shown: fewest === most ? `${most}` : `${fewest}-${most}`,
                  count: onRadar,
                })}
              </p>
            )}
            {modes.length > 1 && (
              <div className="v4-chips" role="group" aria-label={t('sources.filter')}>
                <button
                  type="button"
                  className="v4-chip"
                  aria-pressed={mode === null}
                  onClick={() => setMode(null)}
                >
                  {t('results.allTypes')}
                </button>
                {modes.map((m) => (
                  <button
                    key={m}
                    type="button"
                    className="v4-chip"
                    aria-pressed={mode === m}
                    onClick={() => setMode(m)}
                  >
                    {t(`mode.${m}.label`)}
                  </button>
                ))}
              </div>
            )}
            <ScrollBox label={t('sources.all')}>
              <table className="v4-table h2-sources__table" aria-labelledby="all-h">
                <thead>
                  <tr>
                    <th scope="col">{t('sources.col.source')}</th>
                    <th scope="col">{t('sources.lastCheck')}</th>
                    <th scope="col">{t('sourcesPage.col.mode')}</th>
                    <th scope="col">{t('sources.covers')}</th>
                    <th scope="col">{t('sourcesPage.col.when')}</th>
                    <th scope="col" className="is-num">
                      {t('sourcesPage.col.newThisWeek')}
                    </th>
                    <th scope="col">{t('sourcesPage.col.terms')}</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((s) => (
                    <SourceRow key={s.id} source={s} />
                  ))}
                </tbody>
              </table>
            </ScrollBox>
            <p className="v4-term__note">
              {modes.map((m) => `${t(`mode.${m}.label`)}: ${t(`mode.${m}.hint`)}`).join(' ')}
            </p>
            <p className="v4-term__note">{t('sourcesPage.replicaNote')}</p>
          </>
        )}
      </section>
    </div>
  );
}

/** One source: the short name in Geist (the full name under it when it differs), then the facts in Geist Mono. */
function SourceRow({ source: s }: { source: SourceInfo }) {
  const { t, i18n } = useTranslation();
  const state = stateOf(s);
  const status = !s.enabled
    ? t('sourcesPage.disabled')
    : !s.lastOutcome
      ? t('sourcesPage.neverChecked')
      : t(`outcome.${s.lastOutcome}`);
  const added = newThisWeek(s);
  return (
    <tr className="v4-row">
      <th scope="row">
        <span className="v4-title">
          <ExternalLink url={s.homepage} showHost={false}>
            {s.shortName}
          </ExternalLink>
        </span>
        {s.name !== s.shortName && <span className="v4-sub">{s.name}</span>}
        <SourceNotes source={s} />
        {creditsOf(s) && (
          <span className="v4-sub h2-sources__credits">
            {t('sourcesPage.webCredits')}: <WebCreditsValue source={s} />
          </span>
        )}
      </th>
      <td>
        <span className={`v4-status v4-status--${state}`}>
          <span aria-hidden="true">{GLYPH[state]}</span>
          {status}
        </span>
      </td>
      <td className="v4-nowrap">
        {t(`mode.${s.mode}.label`)}
        {!s.external && <span className="v4-sub">{t('sourcesPage.internal')}</span>}
      </td>
      <td>{s.resourceTypes.map((r) => t(`type.${r}`)).join(', ') || '—'}</td>
      <td>
        <span className="v4-nowrap">
          {s.lastCheckedAt
            ? formatDate(s.lastCheckedAt, i18n.language, 'INSTANT')
            : t('sourcesPage.neverChecked')}
        </span>
        {responseTimeShown(s) && (
          <span className="v4-sub h2-sources__time">
            {t('sourcesPage.responseTime')}: <ResponseTimeValue source={s} />
          </span>
        )}
      </td>
      <td className="is-num">{added ?? '—'}</td>
      <td>
        {s.termsUrl ? (
          <ExternalLink url={s.termsUrl} showHost={false}>
            {t('sourcesPage.terms')}
          </ExternalLink>
        ) : (
          t('common.unknown')
        )}
        {s.attribution && <span className="v4-sub">{s.attribution}</span>}
      </td>
    </tr>
  );
}
