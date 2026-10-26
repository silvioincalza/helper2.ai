import { useId, useState } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { SearchResponse, SourceOutcome } from '@/api/types';
import { formatDate } from '@/lib/format';
import { ModeBadge, OutcomeBadge, ServedFromBadge } from './Badges';
import { Icon } from './Icon';

export function sourceSummary(res: SearchResponse, t: TFunction): string {
  const queried = res.coverage.sourcesQueried;
  const parts = [t('sources.summary', { ok: res.coverage.sourcesSucceeded, total: queried })];
  const counts = new Map<SourceOutcome, number>();
  for (const s of res.sources) if (s.outcome !== 'SUCCEEDED') counts.set(s.outcome, (counts.get(s.outcome) ?? 0) + 1);
  for (const [o, n] of counts) parts.push(t(`sources.count.${o}`, { count: n }));
  const stale = res.sources.filter((s) => s.servedFrom === 'STALE_CACHE').length;
  if (stale) parts.push(t('sources.count.STALE', { count: stale }));
  return parts.join(' · ');
}

export function SourceStatusPanel({ response }: { response: SearchResponse }) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const id = useId();
  const dt = (iso?: string | null) => (iso ? formatDate(iso, i18n.language, 'INSTANT') : '—');
  return (
    <section className="source-panel" aria-label={t('sources.panelTitle')}>
      <button type="button" className="source-summary" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>
        <Icon name={response.coverage.partial ? 'warn' : 'info'} />
        <span>{sourceSummary(response, t)}</span>
        <span className="source-toggle">{open ? t('sources.hide') : t('sources.show')}</span>
      </button>
      {/* Always rendered, hidden when collapsed, so aria-controls always points at an element. */}
      <div id={id} className="table-wrap" hidden={!open}>
        <table>
          <caption className="sr-only">{t('sources.panelTitle')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('sources.col.source')}</th>
              <th scope="col">{t('sources.col.mode')}</th>
              <th scope="col">{t('sources.col.outcome')}</th>
              <th scope="col">{t('sources.col.servedFrom')}</th>
              <th scope="col">{t('sources.col.results')}</th>
              <th scope="col">{t('sources.col.latency')}</th>
              <th scope="col">{t('sources.col.checkedAt')}</th>
              <th scope="col">{t('sources.col.cachedAt')}</th>
            </tr>
          </thead>
          <tbody>
            {response.sources.map((s) => (
              <tr key={s.sourceId}>
                <th scope="row">
                  {s.name}
                  {s.message && <div className="muted small">{s.message}</div>}
                </th>
                <td><ModeBadge mode={s.mode} /></td>
                <td><OutcomeBadge outcome={s.outcome} /></td>
                <td><ServedFromBadge servedFrom={s.servedFrom} /></td>
                <td>{s.resultCount}</td>
                <td>{s.latencyMs == null ? '—' : t('sources.latencyMs', { ms: s.latencyMs })}</td>
                <td>{dt(s.checkedAt)}</td>
                <td>{dt(s.cachedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="muted small">{response.coverage.note}</p>
      </div>
    </section>
  );
}
