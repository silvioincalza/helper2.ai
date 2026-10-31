import { useTranslation } from 'react-i18next';
import type { SourceMode } from '@/api/types';
import { ModeBadge, OutcomeBadge } from '@/components/Badges';
import { ExternalLink } from '@/components/ExternalLink';
import { useSources } from '@/context/SourcesContext';
import { useTitle } from '@/hooks/useTitle';
import { formatDate } from '@/lib/format';
import { NEW_DESIGN_ENABLED } from '@/lib/features';
import { SourcesV2 } from '@/components/sources/SourcesV2';

const MODES: SourceMode[] = ['LIVE', 'INDEXED', 'CURATED', 'DEMO'];

export default function SourcesPage() {
  const { t, i18n } = useTranslation();
  const q = useSources();
  useTitle(t('nav.sources'));
  if (NEW_DESIGN_ENABLED) return <SourcesV2 />;
  return (
    <div>
      <h1>{t('sourcesPage.title')}</h1>
      <p className="lede">{t('sourcesPage.lede')}</p>
      <section aria-labelledby="modes-h" className="panel">
        <h2 id="modes-h" className="h-small">{t('sourcesPage.modes')}</h2>
        <dl className="mode-defs">
          {MODES.map((m) => (
            <div key={m}><dt><ModeBadge mode={m} /></dt><dd>{t(`mode.${m}.hint`)}</dd></div>
          ))}
        </dl>
      </section>
      {q.loading && <p role="status">{t('common.loading')}</p>}
      {!q.loading && q.error !== undefined && (
        <div role="alert" className="state-box state-error">
          <p>{t('sourcesPage.error')}</p>
          <button type="button" className="btn btn-primary" onClick={q.reload}>{t('common.retry')}</button>
        </div>
      )}
      {q.data && (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">{t('sourcesPage.title')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('sources.col.source')}</th>
                <th scope="col">{t('sources.col.mode')}</th>
                <th scope="col">{t('sourcesPage.col.enabled')}</th>
                <th scope="col">{t('sourcesPage.col.types')}</th>
                <th scope="col">{t('sourcesPage.col.terms')}</th>
                <th scope="col">{t('sourcesPage.col.attribution')}</th>
                <th scope="col">{t('sourcesPage.col.lastOutcome')}</th>
                <th scope="col">{t('sources.col.checkedAt')}</th>
              </tr>
            </thead>
            <tbody>
              {q.data.map((s) => (
                <tr key={s.id}>
                  <th scope="row"><ExternalLink url={s.homepage}>{s.name}</ExternalLink></th>
                  <td><ModeBadge mode={s.mode} /></td>
                  <td>{s.enabled ? t('common.yes') : t('sourcesPage.disabled')}</td>
                  <td>{s.resourceTypes.map((r) => t(`type.${r}`)).join(', ')}</td>
                  <td>{s.termsUrl ? <ExternalLink url={s.termsUrl} showHost={false}>{t('sourcesPage.terms')}</ExternalLink> : t('common.unknown')}</td>
                  <td>{s.attribution ?? '—'}</td>
                  <td>{s.lastOutcome ? <OutcomeBadge outcome={s.lastOutcome} /> : t('sourcesPage.neverChecked')}</td>
                  <td>{s.lastCheckedAt ? formatDate(s.lastCheckedAt, i18n.language, 'INSTANT') : t('sourcesPage.neverChecked')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
