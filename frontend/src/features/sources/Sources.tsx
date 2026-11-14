import { AiKey } from '@/ui/AiLabel';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { sourceFamilyValues, sourceModeValues } from '@/api/schema';
import type { SourceInfo, SourceMode } from '@/api/types';
import { ExternalLink } from '@/ui/ExternalLink';
import { TermCommand } from '@/features/home/TermList';
import { toneOf, type DotTone } from '@/features/home/radarLayout';
import { bounds } from '@/features/home/radarRotation';
import { ScrollBox } from '@/ui/ScrollBox';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { api } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useRefreshOnReturn } from '@/hooks/useRefreshOnReturn';
import { useRetry } from '@/hooks/useRetry';
import { clockTime, formatDate } from '@/lib/format';
import { creditsOf, responseTimeShown } from '@/lib/sourceHealth';
import { newThisWeek, radarSources } from '@/lib/sourceCounts';
import { CategoryNotice, categoryOf } from './DiscoveryCategories';
import { ResponseTimeValue, SourceNotes, WebCreditsValue } from './SourceHealth';
import '@/ui/term.css';
import './sources.css';

const STEPS = ['ask', 'merge', 'prove', 'dates'] as const;

function stateOf(s: SourceInfo): DotTone {
  return s.enabled ? toneOf(s) : 'idle';
}

const GLYPH: Record<DotTone, string> = { answering: '✓', warn: '!', failed: '×', idle: '○' };

export function Sources() {
  const { t } = useTranslation();
  const [listedAt, setListedAt] = useState(() => new Date().toISOString());
  const q = useApi('sources', (signal) =>
    api.listSources(signal).then((list) => {
      setListedAt(new Date().toISOString());
      return list;
    }),
  );
  const { retry, focusRef } = useRetry(q.reload, q.loading);
  useRefreshOnReturn(q.reload);
  const list = q.data ?? q.previous;
  const [mode, setMode] = useState<SourceMode | null>(null);
  const all = list ?? [];
  const modes = sourceModeValues.filter((m) => all.some((s) => s.mode === m));
  const shown = mode ? all.filter((s) => s.mode === mode) : all;
  const others = shown.filter((s) => !s.family);
  const families = sourceFamilyValues.filter((f) => shown.some((s) => s.family === f));
  const count = (st: DotTone) => all.filter((s) => stateOf(s) === st).length;
  const onRadar = radarSources(all).length;
  const { fewest, most } = bounds(onRadar);
  return (
    <div className="h2-app h2-term h2-sources">
      <header className="h2-term__head">
        <h1>{t('sourcesPage.title')}</h1>
        <p className="h2-term__lede">{t('sourcesPage.lede')}</p>
        <p className="muted small">
          <AiKey />
        </p>
      </header>

      <section aria-labelledby="how-h">
        <h2 id="how-h" className="h2-term__sub eyebrow">
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
        <div className="h2-term__bar">
          <h2 id="all-h" ref={focusRef} tabIndex={-1}>
            {t('sources.all')}
          </h2>
          <TermCommand command="helper2 sources --all" total={list ? all.length : null} />
        </div>
        {q.loading && !list && (
          <p role="status" className="h2-term__state">
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
                  time: clockTime(listedAt, false),
                })}
              </p>
            )}
            <p className="h2-sources__summary">
              <span>{t('sources.summary', { ok: count('answering'), total: all.length })}</span>
              {count('warn') > 0 && (
                <span className="h2-status h2-status--warn">
                  <span aria-hidden="true">{GLYPH.warn}</span>
                  {t('sources.warn', { count: count('warn') })}
                </span>
              )}
              {count('failed') > 0 && (
                <span className="h2-status h2-status--failed">
                  <span aria-hidden="true">{GLYPH.failed}</span>
                  {t('sources.failed', { count: count('failed') })}
                </span>
              )}
              {count('idle') > 0 && (
                <span className="h2-status h2-status--idle">
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
              <div className="chips" role="group" aria-label={t('sources.filter')}>
                <button
                  type="button"
                  className="chip"
                  aria-pressed={mode === null}
                  onClick={() => setMode(null)}
                >
                  {t('results.allTypes')}
                </button>
                {modes.map((m) => (
                  <button
                    key={m}
                    type="button"
                    className="chip"
                    aria-pressed={mode === m}
                    onClick={() => setMode(m)}
                  >
                    {t(`mode.${m}.label`)}
                  </button>
                ))}
              </div>
            )}
            <ScrollBox label={t('sources.all')}>
              <table className="h2-table h2-sources__table" aria-labelledby="all-h">
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
                  {others.map((s) => (
                    <SourceRow key={s.id} source={s} />
                  ))}
                </tbody>
                {families.map((family) => (
                  <tbody key={family} className="h2-sources__family">
                    <tr>
                      <th scope="rowgroup" colSpan={7}>
                        {t('sourcesPage.discovery.group', {
                          family: t(`sourcesPage.family.${family}`),
                        })}
                      </th>
                    </tr>
                    {shown
                      .filter((s) => s.family === family)
                      .map((s) => (
                        <SourceRow key={s.id} source={s} />
                      ))}
                  </tbody>
                ))}
              </table>
            </ScrollBox>
            {families.length > 0 && (
              <p className="h2-term__note">
                {t('sourcesPage.discovery.lede')} {t('sourcesPage.discovery.lists')}{' '}
                {t('sourcesPage.discovery.products')}
              </p>
            )}
            <p className="h2-term__note">
              {modes.map((m) => `${t(`mode.${m}.label`)}: ${t(`mode.${m}.hint`)}`).join(' ')}
            </p>
            <p className="h2-term__note">{t('sourcesPage.replicaNote')}</p>
          </>
        )}
      </section>
    </div>
  );
}

function SourceRow({ source: s }: { source: SourceInfo }) {
  const { t } = useTranslation();
  const state = stateOf(s);
  const status = !s.enabled
    ? t('sourcesPage.disabled')
    : !s.lastOutcome
      ? t('sourcesPage.neverChecked')
      : t(`outcome.${s.lastOutcome}`);
  const added = newThisWeek(s);
  return (
    <tr className="h2-term-row">
      <th scope="row">
        <span className="h2-title">
          <ExternalLink url={s.homepage} showHost={false}>
            {s.shortName}
          </ExternalLink>
        </span>
        {s.name !== s.shortName && <span className="h2-sub">{s.name}</span>}
        {s.family && <CategoryNotice category={categoryOf(s)} />}
        <SourceNotes source={s} />
        {creditsOf(s) && (
          <span className="h2-sub h2-sources__credits">
            {t('sourcesPage.webCredits')}: <WebCreditsValue source={s} />
          </span>
        )}
      </th>
      <td>
        <span className={`h2-status h2-status--${state}`}>
          <span aria-hidden="true">{GLYPH[state]}</span>
          {status}
        </span>
      </td>
      <td className="h2-nowrap">
        {t(`mode.${s.mode}.label`)}
        {!s.external && <span className="h2-sub">{t('sourcesPage.internal')}</span>}
      </td>
      <td>{s.resourceTypes.map((r) => t(`type.${r}`)).join(', ') || '—'}</td>
      <td>
        <span className="h2-nowrap">
          {s.lastCheckedAt ? formatDate(s.lastCheckedAt, 'INSTANT') : t('sourcesPage.neverChecked')}
        </span>
        {responseTimeShown(s) && (
          <span className="h2-sub h2-sources__time">
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
        {s.attribution && <span className="h2-sub">{s.attribution}</span>}
      </td>
    </tr>
  );
}
