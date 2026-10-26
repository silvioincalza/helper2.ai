import { Link, useParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { Relationship } from '@/api/types';
import { OriginLabel, VerificationBadge } from '@/components/Badges';
import { EvidenceList } from '@/components/EvidenceList';
import { ExternalLink } from '@/components/ExternalLink';
import { canGuide } from '@/components/GuideMe';
import { SetupGuidePanel } from '@/components/SetupGuide';
import { Icon } from '@/components/Icon';
import { ReportForm } from '@/components/ReportForm';
import { ResultCardView } from '@/components/ResultCardView';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
import { formatDate } from '@/lib/format';

function RelList({ items }: { items: Relationship[] }) {
  const { t } = useTranslation();
  return (
    <ul className="plain-list rel-list">
      {items.map((r, i) => (
        <li key={`${r.kind}-${r.targetTitle}-${i}`}>
          <strong>{t(`relationship.${r.kind}`)}</strong>{' '}
          {r.targetId ? <Link to={`/resource/${r.targetId}`}>{r.targetTitle}</Link> : r.targetTitle}{' '}
          <VerificationBadge verification={r.verification} />
          <EvidenceList evidence={r.evidence} />
        </li>
      ))}
    </ul>
  );
}

export default function ResourcePage() {
  const { id = '' } = useParams();
  const { t, i18n } = useTranslation();
  const q = useApi(`resource/${id}`, (signal) => api.getResource(id, signal));
  const d = q.data;
  useTitle(d?.card.title ?? '');

  if (q.loading) return <p role="status" aria-busy="true">{t('common.loading')}</p>;
  if (q.error !== undefined || !d) {
    const notFound = q.error instanceof ApiError && q.error.status === 404;
    return (
      <div role="alert" className="state-box state-error">
        <h1 className="h-small">{notFound ? t('detail.notFound') : t('detail.errorTitle')}</h1>
        {!notFound && <button type="button" className="btn btn-primary" onClick={q.reload}>{t('common.retry')}</button>}
        <p><Link to="/">{t('detail.backToSearch')}</Link></p>
      </div>
    );
  }

  const confirmed = d.relationships.filter((r) => r.verification === 'VERIFIED');
  const candidates = d.relationships.filter((r) => r.verification === 'UNVERIFIED' || r.verification === 'CONFLICTING');

  return (
    <div className="detail">
      <p><Link to="/">{t('detail.backToSearch')}</Link></p>
      <ResultCardView card={d.card} detailed />
      {canGuide(d.card) && <SetupGuidePanel id={id} title={d.card.title} />}

      <section aria-labelledby="sum-h" className="panel">
        <h2 id="sum-h">{t('detail.summary')}</h2>
        <p className="muted small">{t(`enrichment.${d.enrichmentStatus}`)}</p>
        {d.summary ? (
          <>
            <ul className="plain-list claims">
              {d.summary.claims.map((c, i) => (
                <li key={i}>
                  <p>{c.text}</p>
                  <details>
                    <summary>{t('detail.citations', { count: c.evidence.length })}</summary>
                    <EvidenceList evidence={c.evidence} />
                  </details>
                </li>
              ))}
            </ul>
            <p className="muted small">
              {t('detail.summaryMeta', { model: d.summary.model, version: d.summary.promptVersion, date: formatDate(d.summary.generatedAt, i18n.language, 'INSTANT') })}
            </p>
          </>
        ) : (
          <p>{t('detail.noSummary')}</p>
        )}
      </section>

      <section aria-labelledby="as-h" className="panel">
        <h2 id="as-h">{t('detail.assertions')}</h2>
        {d.assertions.length === 0 ? <p className="muted">{t('detail.noAssertions')}</p> : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('detail.col.field')}</th>
                  <th scope="col">{t('detail.col.value')}</th>
                  <th scope="col">{t('detail.col.origin')}</th>
                  <th scope="col">{t('detail.col.verification')}</th>
                  <th scope="col">{t('detail.col.evidence')}</th>
                </tr>
              </thead>
              <tbody>
                {d.assertions.map((a, i) => (
                  <tr key={`${a.field}-${i}`}>
                    <th scope="row">{a.field}</th>
                    <td>{a.value}</td>
                    <td><OriginLabel origin={a.origin} />{a.sourceId && <div className="muted small">{a.sourceId}</div>}</td>
                    <td><VerificationBadge verification={a.verification} /></td>
                    <td><EvidenceList evidence={a.evidence} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section aria-labelledby="rel-h" className="panel">
        <h2 id="rel-h">{t('detail.relationships')}</h2>
        {confirmed.length === 0 ? <p className="muted">{t('detail.noVerified')}</p> : <RelList items={confirmed} />}
      </section>
      {candidates.length > 0 && (
        <section aria-labelledby="cand-h" className="panel panel-candidates">
          <h2 id="cand-h"><Icon name="question" /> {t('detail.candidates')}</h2>
          <p className="muted">{t('detail.candidatesHint')}</p>
          <RelList items={candidates} />
        </section>
      )}

      <section aria-labelledby="rec-h" className="panel">
        <h2 id="rec-h">{t('detail.sourceRecords')}</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('sources.col.source')}</th>
                <th scope="col">{t('detail.col.externalId')}</th>
                <th scope="col">{t('detail.col.retrieved')}</th>
                <th scope="col">{t('detail.col.fingerprint')}</th>
              </tr>
            </thead>
            <tbody>
              {d.sourceRecords.map((r) => (
                <tr key={`${r.sourceId}-${r.externalId}`}>
                  <th scope="row">{r.sourceId}</th>
                  <td><ExternalLink url={r.url} showHost={false}>{r.externalId}</ExternalLink></td>
                  <td>{formatDate(r.retrievedAt, i18n.language, 'INSTANT')}</td>
                  <td><code title={r.fingerprint}>{r.fingerprint.slice(0, 12)}</code></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section aria-labelledby="report-h" className="panel">
        <h2 id="report-h">{t('report.title')}</h2>
        <ReportForm resourceId={d.card.id} />
      </section>
    </div>
  );
}
