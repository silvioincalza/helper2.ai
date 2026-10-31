import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SourceInfo, SourceMode } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { TypeTag } from '@/components/ui/TypeTag';
import { useSources } from '@/context/SourcesContext';
import { formatDate } from '@/lib/format';
import '@/components/saved/saved-v2.css';
import './sources-v2.css';

const MODES: SourceMode[] = ['LIVE', 'INDEXED', 'CURATED', 'DEMO'];
const STEPS = ['ask', 'merge', 'prove', 'dates'] as const;

/** ok: the last check succeeded. warn: rate limited or skipped. failed: failed or timed out. */
export type SourceState = 'ok' | 'warn' | 'failed' | 'never' | 'off';

export function sourceState(s: SourceInfo): SourceState {
  if (!s.enabled) return 'off';
  if (!s.lastOutcome) return 'never';
  if (s.lastOutcome === 'SUCCEEDED') return 'ok';
  if (s.lastOutcome === 'RATE_LIMITED' || s.lastOutcome === 'SKIPPED') return 'warn';
  return 'failed';
}

/**
 * Design v2 sources page (ADR 0040, 10): how a search works, the source modes in use, the last check of every
 * source in words, and one card per source with what it covers, its terms and attribution.
 */
export function SourcesV2() {
  const { t, i18n } = useTranslation();
  const q = useSources();
  const [mode, setMode] = useState<SourceMode | null>(null);
  const all = q.data ?? [];
  const modes = MODES.filter((m) => all.some((s) => s.mode === m));
  const shown = mode ? all.filter((s) => s.mode === mode) : all;
  const count = (st: SourceState) => all.filter((s) => sourceState(s) === st).length;
  const status = (s: SourceInfo) => {
    const st = sourceState(s);
    if (st === 'off') return t('sourcesPage.disabled');
    if (st === 'never') return t('sourcesPage.neverChecked');
    return t(`outcome.${s.lastOutcome}`);
  };
  return (
    <div className="h2-sources">
      <header className="h2-page-head">
        <h1>{t('sourcesPage.title')}</h1>
        <p className="h2-page-head__lede">{t('sourcesPage.lede')}</p>
      </header>

      <section aria-labelledby="how-h">
        <h2 id="how-h" className="h2-sources__h">{t('sourcesV2.how')}</h2>
        <ol className="h2-sources__steps">
          {STEPS.map((k, i) => (
            <li key={k} className="h2-sources__step">
              <span className="h2-sources__n">{String(i + 1).padStart(2, '0')}</span>
              <h3>{t(`sourcesV2.steps.${k}.title`)}</h3>
              <p>{t(`sourcesV2.steps.${k}.text`)}</p>
            </li>
          ))}
        </ol>
      </section>

      {modes.length > 0 && (
        <section aria-labelledby="modes-h">
          <h2 id="modes-h" className="h2-sources__h">{t('sourcesPage.modes')}</h2>
          <dl className="h2-sources__modes">
            {modes.map((m) => (
              <div key={m}><dt><span className="h2-mode">{t(`mode.${m}.label`)}</span></dt><dd>{t(`mode.${m}.hint`)}</dd></div>
            ))}
          </dl>
        </section>
      )}

      <section aria-labelledby="all-h">
        <h2 id="all-h" className="h2-sources__h">{t('sourcesV2.all')}</h2>
        {q.loading && <p role="status">{t('common.loading')}</p>}
        {!q.loading && q.error !== undefined && (
          <div role="alert" className="state-box state-error">
            <p>{t('sourcesPage.error')}</p>
            <button type="button" className="btn btn-primary" onClick={q.reload}>{t('common.retry')}</button>
          </div>
        )}
        {q.data && (
          <>
            <p className="h2-sources__summary">
              <strong>{t('sourcesV2.summary', { ok: count('ok'), total: all.length })}</strong>
              {count('warn') > 0 && <span><span className="h2-dot is-warn" aria-hidden="true" />{t('sourcesV2.warn', { count: count('warn') })}</span>}
              {count('failed') > 0 && <span><span className="h2-dot is-failed" aria-hidden="true" />{t('sourcesV2.failed', { count: count('failed') })}</span>}
              {count('never') > 0 && <span><span className="h2-dot is-never" aria-hidden="true" />{t('sourcesV2.never', { count: count('never') })}</span>}
            </p>
            {modes.length > 1 && (
              <div className="h2-sources__chips" role="group" aria-label={t('sourcesV2.filter')}>
                <button type="button" className="h2-chip" aria-pressed={mode === null} onClick={() => setMode(null)}>{t('resultsV2.allTypes')}</button>
                {modes.map((m) => (
                  <button key={m} type="button" className="h2-chip" aria-pressed={mode === m} onClick={() => setMode(m)}>{t(`mode.${m}.label`)}</button>
                ))}
              </div>
            )}
            <ul className="h2-sources__grid">
              {shown.map((s) => (
                <li key={s.id} className="h2-src-card">
                  <div className="h2-src-card__top">
                    <h3 className="h2-src-card__name"><ExternalLink url={s.homepage} showHost={false}>{s.name}</ExternalLink></h3>
                    <span className="h2-mode">{t(`mode.${s.mode}.label`)}</span>
                    <span className={`h2-src-card__status is-${sourceState(s)}`}><span className="h2-dot" aria-hidden="true" />{status(s)}</span>
                  </div>
                  {s.resourceTypes.length > 0 && (
                    <div className="h2-src-card__covers" aria-label={t('sourcesV2.covers')}>
                      {s.resourceTypes.map((r) => <TypeTag key={r} type={r} />)}
                    </div>
                  )}
                  <dl className="h2-src-card__facts">
                    <dt>{t('sourcesPage.col.terms')}</dt>
                    <dd>{s.termsUrl ? <ExternalLink url={s.termsUrl} showHost={false}>{t('sourcesPage.terms')}</ExternalLink> : t('common.unknown')}</dd>
                    <dt>{t('sourcesPage.col.attribution')}</dt>
                    <dd>{s.attribution ?? '—'}</dd>
                    <dt>{t('sourcesV2.lastCheck')}</dt>
                    <dd className="h2-src-card__mono">{s.lastCheckedAt ? formatDate(s.lastCheckedAt, i18n.language, 'INSTANT') : t('sourcesPage.neverChecked')}</dd>
                  </dl>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
    </div>
  );
}
