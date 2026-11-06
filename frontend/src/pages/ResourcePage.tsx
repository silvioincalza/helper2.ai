import { useEffect } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { Relationship, ResourceDetail } from '@/api/types';
import { OriginLabel, VerificationBadge } from '@/components/Badges';
import { EvidenceList } from '@/components/EvidenceList';
import { ExternalLink } from '@/components/ExternalLink';
import { Icon } from '@/components/Icon';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { ReportForm } from '@/components/ReportForm';
import { Robots } from '@/components/Robots';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
import { formatDate } from '@/lib/format';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { FieldStates } from '@/components/results/FieldStates';
import { Resource } from '@/components/results/Resource';
import { AdSenseSlot } from '@/components/AdSenseSlot';
import { resourcePath } from '@/lib/searchState';

function RelList({ items }: { items: Relationship[] }) {
  const { t } = useTranslation();
  return (
    <ul className="plain-list rel-list">
      {items.map((r, i) => (
        <li key={`${r.kind}-${r.targetTitle}-${i}`}>
          <strong>{t(`relationship.${r.kind}`)}</strong>{' '}
          {r.targetId ? (
            <Link to={resourcePath(r.targetId, r.targetTitle)}>{r.targetTitle}</Link>
          ) : (
            r.targetTitle
          )}{' '}
          <VerificationBadge verification={r.verification} />
          {r.origin === 'SELF_DECLARED' && (
            <>
              {' '}
              <OriginLabel origin={r.origin} />
            </>
          )}
          <EvidenceList evidence={r.evidence} />
        </li>
      ))}
    </ul>
  );
}

/** Keyed by id: a new resource starts with fresh state (report form, setup panel), not the previous one's. */
export default function ResourcePage() {
  const { id = '' } = useParams();
  return <ResourceView key={id} id={id} />;
}

/**
 * Once the resource is here, the address bar shows its readable address (ADR 0050): a bare id, an old slug or a
 * merged id is replaced, as the server's 301 does for a page load. The history entry is replaced, not added.
 */
function useReadableAddress(d: ResourceDetail | undefined) {
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => {
    if (!d) return;
    const readable = resourcePath(d.card.id, d.card.title);
    if (location.pathname === readable) return;
    void navigate(`${readable}${location.search}${location.hash}`, {
      replace: true,
      state: location.state,
    });
  }, [d, location.pathname, location.search, location.hash, location.state, navigate]);
}

function ResourceView({ id }: { id: string }) {
  const { t } = useTranslation();
  const q = useApi(`resource/${id}`, (signal) => api.getResource(id, signal));
  const d = q.data;
  useTitle(d?.card.title ?? '');
  useReadableAddress(d);

  if (q.loading)
    return (
      <p role="status" aria-busy="true" className="page-loading">
        {t('common.loading')}
      </p>
    );
  if (q.error !== undefined || !d) {
    const notFound = q.error instanceof ApiError && q.error.status === 404;
    return (
      <>
        {/* The server answers 200 for every page: this keeps a removed resource out of the index (a soft 404). */}
        {notFound && <Robots content="noindex" />}
        <StatePanel
          tone="error"
          level={1}
          title={notFound ? t('detail.notFound') : t('detail.errorTitle')}
          next={
            <>
              {!notFound && <RetryButton onClick={q.reload} />}
              <Link to="/">{t('detail.backToSearch')}</Link>
            </>
          }
        />
      </>
    );
  }

  return (
    <Resource detail={d}>
      <DetailSections detail={d} />
    </Resource>
  );
}

/** Summary, recorded facts, relationships, source records and the report form. */
function DetailSections({ detail: d }: { detail: ResourceDetail }) {
  const { t, i18n } = useTranslation();
  const confirmed = d.relationships.filter((r) => r.verification === 'VERIFIED');
  const candidates = d.relationships.filter(
    (r) => r.verification === 'UNVERIFIED' || r.verification === 'CONFLICTING',
  );
  return (
    <>
      <section aria-labelledby="fields-h" className="panel">
        <h2 id="fields-h">{t('detail.fields')}</h2>
        <FieldStates detail={d} />
      </section>

      <section aria-labelledby="sum-h" className="panel">
        <h2 id="sum-h">{t('detail.summary')}</h2>
        <p className="muted small">{t(`enrichment.${d.enrichmentStatus}`)}</p>
        {d.summary ? (
          <>
            <ul className="plain-list claims">
              {d.summary.claims.map((c, i) => (
                <li key={i}>
                  <p {...aiMark(d.summary?.model)}>{c.text}</p>
                  {c.selfDeclared && <p className="muted small">{t('detail.selfDeclaredClaim')}</p>}
                  <details>
                    <summary>{t('detail.citations', { count: c.evidence.length })}</summary>
                    <EvidenceList evidence={c.evidence} />
                  </details>
                </li>
              ))}
            </ul>
            <p className="muted small">
              {t('detail.summaryMeta', {
                model: d.summary.model,
                version: d.summary.promptVersion,
                date: formatDate(d.summary.generatedAt, i18n.language, 'INSTANT'),
              })}
            </p>
          </>
        ) : (
          <p>{t('detail.noSummary')}</p>
        )}
      </section>

      <section aria-labelledby="as-h" className="panel">
        <h2 id="as-h">{t('detail.assertions')}</h2>
        {d.assertions.length === 0 ? (
          <p className="muted">{t('detail.noAssertions')}</p>
        ) : (
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
                    <td {...(isAiOrigin(a.origin) ? aiMark() : {})}>{a.value}</td>
                    <td>
                      <OriginLabel origin={a.origin} />
                      {a.sourceId && <div className="muted small">{a.sourceId}</div>}
                    </td>
                    <td>
                      <VerificationBadge verification={a.verification} />
                    </td>
                    <td>
                      <EvidenceList evidence={a.evidence} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section aria-labelledby="rel-h" className="panel">
        <h2 id="rel-h">{t('detail.relationships')}</h2>
        {confirmed.length === 0 ? (
          <p className="muted">{t('detail.noVerified')}</p>
        ) : (
          <RelList items={confirmed} />
        )}
      </section>
      {candidates.length > 0 && (
        <section aria-labelledby="cand-h" className="panel panel-candidates">
          <h2 id="cand-h">
            <Icon name="question" /> {t('detail.candidates')}
          </h2>
          <p className="muted">{t('detail.candidatesHint')}</p>
          <RelList items={candidates} />
        </section>
      )}

      {/* The one ad of a resource page, between the resource's own sections, never next to the AI summary. */}
      <AdSenseSlot />

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
                  <td>
                    <ExternalLink url={r.url} showHost={false}>
                      {r.externalId}
                    </ExternalLink>
                  </td>
                  <td>{formatDate(r.retrievedAt, i18n.language, 'INSTANT')}</td>
                  <td>
                    <code title={r.fingerprint}>{r.fingerprint.slice(0, 12)}</code>
                  </td>
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
    </>
  );
}
