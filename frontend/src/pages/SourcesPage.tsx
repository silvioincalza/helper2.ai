import { useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { SourceInfo, SourceMode } from '@/api/types';
import { ModeBadge } from '@/components/Badges';
import { ExternalLink } from '@/components/ExternalLink';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { SourceTiles } from '@/components/ui/SourceTiles';
import { useSources } from '@/context/SourcesContext';
import { useRetry } from '@/hooks/useRetry';
import { useTitle } from '@/hooks/useTitle';

const MODES: SourceMode[] = ['LIVE', 'INDEXED', 'CURATED', 'DEMO'];

/**
 * Every source Helper2.ai can search (ADR 0040, 10): how each one answered last as a tile, what the modes mean, and
 * what each source covers, under which terms and attribution. Try again keeps focus on the page heading.
 */
export default function SourcesPage() {
  const { t } = useTranslation();
  const q = useSources();
  const { retry, focusRef } = useRetry(q.reload, q.loading);
  useTitle(t('nav.sources'));
  return (
    <div className="sources-page">
      <h1 ref={focusRef} tabIndex={-1}>{t('sourcesPage.title')}</h1>
      <p className="lede">{t('sourcesPage.lede')}</p>
      {q.loading && (
        <div aria-busy="true">
          <span className="sr-only">{t('common.loading')}</span>
          <div className="skeleton-row sources-page__skeleton" aria-hidden="true" />
        </div>
      )}
      {!q.loading && q.error !== undefined && (
        <StatePanel tone="error" level={2} title={t('sourcesPage.error')} next={<RetryButton onClick={retry} />}>
          <p>{t('sourcesPage.errorBody')}</p>
        </StatePanel>
      )}
      {!q.loading && q.data?.length === 0 && (
        <StatePanel tone="notice" level={2} title={t('sourcesPage.empty')}
          next={<Link to="/" className="btn">{t('notFound.home')}</Link>} />
      )}
      {!q.loading && q.data !== undefined && q.data.length > 0 && <Registry sources={q.data} />}
    </div>
  );
}

function Registry({ sources }: { sources: SourceInfo[] }) {
  const { t } = useTranslation();
  // The ages are read once, when the list is shown.
  const [now] = useState(Date.now);
  return (
    <>
      <section className="sources-page__part" aria-labelledby="sources-last-h">
        <h2 id="sources-last-h" className="preview-label">{t('sourcesPage.last')}</h2>
        <SourceTiles registry={sources} now={now} label={t('sourcesPage.last')} />
        <p className="muted small">{t('sourcesPage.lastNote')}</p>
      </section>
      <section className="sources-page__part" aria-labelledby="sources-modes-h">
        <h2 id="sources-modes-h" className="preview-label">{t('sourcesPage.modes')}</h2>
        <dl className="sources-page__modes">
          {MODES.map((m) => (
            <div key={m}><dt><ModeBadge mode={m} /></dt><dd>{t(`mode.${m}.hint`)}</dd></div>
          ))}
        </dl>
      </section>
      <section className="sources-page__part" aria-labelledby="sources-covers-h">
        <h2 id="sources-covers-h" className="preview-label">{t('sourcesPage.covers')}</h2>
        <div className="sources-page__table">
          <table aria-labelledby="sources-covers-h">
            <thead>
              <tr>
                <th scope="col">{t('sourcesPage.col.source')}</th>
                <th scope="col">{t('sourcesPage.col.mode')}</th>
                <th scope="col">{t('sourcesPage.col.types')}</th>
                <th scope="col">{t('sourcesPage.col.terms')}</th>
                <th scope="col">{t('sourcesPage.col.attribution')}</th>
              </tr>
            </thead>
            <tbody>
              {sources.map((s) => (
                <tr key={s.id}>
                  <th scope="row"><ExternalLink url={s.homepage} showHost={false}>{s.name}</ExternalLink></th>
                  <td><ModeBadge mode={s.mode} /></td>
                  <td>{s.resourceTypes.map((r) => t(`type.${r}`)).join(', ')}</td>
                  <td>{s.termsUrl ? <ExternalLink url={s.termsUrl} showHost={false}>{t('sourcesPage.terms')}</ExternalLink> : t('common.unknown')}</td>
                  <td>{s.attribution ?? t('sourcesPage.noAttribution')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
