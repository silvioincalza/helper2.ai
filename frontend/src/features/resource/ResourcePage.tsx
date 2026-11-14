import { useEffect } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { Relationship, ResourceDetail } from '@/api/types';
import { OriginLabel, VerificationBadge } from '@/ui/Badges';
import { EvidenceList } from '@/features/resource/EvidenceList';
import { Icon } from '@/ui/Icon';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { ReportForm } from '@/features/resource/ReportForm';
import { Robots } from '@/shell/Robots';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
import { formatDate } from '@/lib/format';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { WhereFrom } from '@/features/resource/WhereFrom';
import { cardHeadFor, Resource } from '@/features/resource/Resource';
import { AdSenseSlot } from '@/features/monetisation/AdSenseSlot';
import { resourcePath } from '@/lib/searchState';

function RelList({ items }: { items: Relationship[] }) {
  const { t } = useTranslation();
  return (
    <ul className="plain-list rel-list">
      {items.map((r, i) => {
        const ai = r.origin !== undefined && isAiOrigin(r.origin);
        return (
          <li key={`${r.kind}-${r.targetTitle}-${i}`}>
            <span {...(ai ? aiMark() : {})}>
              <strong>{t(`relationship.${r.kind}`)}</strong>{' '}
              {r.targetId ? (
                <Link to={resourcePath(r.targetId, r.targetTitle)}>{r.targetTitle}</Link>
              ) : (
                r.targetTitle
              )}
            </span>{' '}
            <VerificationBadge verification={r.verification} />
            {ai && r.origin && (
              <>
                {' '}
                <OriginLabel origin={r.origin} />
              </>
            )}
            <EvidenceList evidence={r.evidence} />
          </li>
        );
      })}
    </ul>
  );
}

export default function ResourcePage() {
  const { id = '' } = useParams();
  return <ResourceView key={id} id={id} />;
}

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
  const location = useLocation();
  const q = useApi(`resource/${id}`, (signal) => api.getResource(id, signal));
  const d = q.data;
  useTitle(d?.card.title ?? '');
  useReadableAddress(d);

  const head = d?.card ?? cardHeadFor(id, location.state);
  if (!q.loading && (q.error !== undefined || !d)) {
    const notFound = q.error instanceof ApiError && q.error.status === 404;
    return (
      <>
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

  if (!head)
    return (
      <p role="status" aria-busy="true" className="page-loading">
        {t('common.loading')}
      </p>
    );
  return (
    <Resource head={head} detail={d}>
      {d && <DetailSections detail={d} />}
    </Resource>
  );
}

function DetailSections({ detail: d }: { detail: ResourceDetail }) {
  const { t } = useTranslation();
  const confirmed = d.relationships.filter((r) => r.verification === 'VERIFIED');
  const candidates = d.relationships.filter(
    (r) => r.verification === 'UNVERIFIED' || r.verification === 'CONFLICTING',
  );
  return (
    <>
      {d.summary && (
        <section aria-labelledby="sum-h" className="panel">
          <h2 id="sum-h">{t('detail.summary')}</h2>
          <p className="muted small">{t(`enrichment.${d.enrichmentStatus}`)}</p>
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
              date: formatDate(d.summary.generatedAt, 'INSTANT'),
            })}
          </p>
        </section>
      )}

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

      <AdSenseSlot />

      <WhereFrom detail={d} />

      <section aria-labelledby="report-h" className="panel">
        <h2 id="report-h">{t('report.title')}</h2>
        <ReportForm resourceId={d.card.id} />
      </section>
    </>
  );
}
