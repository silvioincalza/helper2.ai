import { useId } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { Relationship, ResourceDetail } from '@/api/types';
import { OriginLabel, VerificationBadge } from '@/components/Badges';
import { EvidenceList } from '@/components/EvidenceList';
import { ExternalLink } from '@/components/ExternalLink';
import { Part, ResourceOverview, type FromResults } from '@/components/ResourceOverview';
import { ReportForm } from '@/components/ReportForm';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { useTitle } from '@/hooks/useTitle';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';

/** Page-only parts sit under the h1, at the level of the overview's part labels. */
const LEVEL = 2;

/**
 * `/resource/:id` (ADR 0040, 4 and 8): the overview the results preview shows, as a page, then what only the page
 * has: the AI summary with its citations, what each source says with its evidence, relationships, the source records
 * and the report form. Loading, not found and an error are designed states; Try again keeps focus on the page.
 */
export default function ResourcePage() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const q = useApi(`resource/${id}`, (signal) => api.getResource(id, signal));
  const { retry, focusRef } = useRetry(q.reload, q.loading);
  const titleId = useId();
  const notFound = q.error instanceof ApiError && q.error.status === 404;
  const d = q.data;
  useTitle(d?.card.title ?? (q.error === undefined ? '' : t(notFound ? 'detail.notFound' : 'detail.errorTitle')));

  if (q.loading) {
    return (
      <div className="resource-page" aria-busy="true">
        <p role="status" className="muted">{t('common.loading')}</p>
        {Array.from({ length: 4 }, (_, i) => <div key={i} className="skeleton-row resource-skeleton" aria-hidden="true" />)}
      </div>
    );
  }
  if (notFound) {
    return (
      <StatePanel tone="notice" level={1} title={t('detail.notFound')} headingRef={focusRef} next={<HomeLink primary />}>
        <p>{t('detail.notFoundBody')}</p>
      </StatePanel>
    );
  }
  if (q.error !== undefined || !d) {
    return (
      <StatePanel tone="error" level={1} title={t('detail.errorTitle')} headingRef={focusRef} next={<><RetryButton onClick={retry} /><HomeLink /></>}>
        <p>{q.error instanceof ApiError && q.error.problem?.detail ? q.error.problem.detail : t('detail.errorBody')}</p>
      </StatePanel>
    );
  }

  return (
    <article className="resource-page" aria-labelledby={titleId}>
      <BackToResults />
      <ResourceOverview card={d.card} page titleId={titleId} titleRef={focusRef} />
      <AiSummary detail={d} />
      <SourcesSay assertions={d.assertions} />
      <Relationships relationships={d.relationships} />
      <SourceRecords detail={d} />
      <Part label={t('report.title')} level={LEVEL}>
        <ReportForm resourceId={d.card.id} />
      </Part>
    </article>
  );
}

function HomeLink({ primary = false }: { primary?: boolean }) {
  const { t } = useTranslation();
  return <Link to="/" className={primary ? 'btn btn-primary' : undefined}>{t('notFound.home')}</Link>;
}

/**
 * Followed from the results ("Full details"), the page steps back to them like the browser's Back: the same search,
 * selection and scroll position. Opened any other way, there is nothing to go back to; the header has the box.
 */
function BackToResults() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const from = (useLocation().state as Partial<FromResults> | null)?.from;
  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//')) return null;
  return (
    <Link to={from} className="resource-back" onClick={(e) => { e.preventDefault(); void navigate(-1); }}>
      ← {t('detail.backToResults')}
    </Link>
  );
}

/** The AI-written summary (ADR 0019): labelled, marked with its model (ADR 0038), each claim with its citations. */
function AiSummary({ detail }: { detail: ResourceDetail }) {
  const { t, i18n } = useTranslation();
  const { summary } = detail;
  return (
    <Part label={t('detail.summary')} level={LEVEL}>
      <p className="muted small">{t(`enrichment.${detail.enrichmentStatus}`)}</p>
      {summary ? (
        <>
          <ul className="plain-list resource-claims">
            {summary.claims.map((c, i) => (
              <li key={i}>
                <p {...aiMark(summary.model)}>{c.text}</p>
                <details className="resource-cites">
                  <summary>{t('detail.citations', { count: c.evidence.length })}</summary>
                  <EvidenceList evidence={c.evidence} />
                </details>
              </li>
            ))}
          </ul>
          <p className="muted small">
            {t('detail.summaryMeta', { model: summary.model, version: summary.promptVersion, date: formatDate(summary.generatedAt, i18n.language, 'INSTANT') })}
          </p>
        </>
      ) : (
        <p>{t('detail.noSummary')}</p>
      )}
    </Part>
  );
}

/** Each stated value (ADR 0004): where it came from, whether it is verified, and the evidence. AI-extracted values are marked. */
function SourcesSay({ assertions }: { assertions: ResourceDetail['assertions'] }) {
  const { t } = useTranslation();
  return (
    <Part label={t('detail.assertions')} level={LEVEL}>
      {assertions.length === 0 ? <p className="muted">{t('detail.noAssertions')}</p> : (
        <ul className="plain-list resource-says">
          {assertions.map((a, i) => (
            <li key={`${a.field}-${i}`} className="resource-say">
              <p className="resource-say__head"><span className="resource-say__field">{a.field}</span> <VerificationBadge verification={a.verification} /></p>
              <p className="resource-say__value" {...(isAiOrigin(a.origin) ? aiMark() : {})}>{a.value}</p>
              <p className="muted small"><OriginLabel origin={a.origin} />{a.sourceId && <> · {a.sourceId}</>}</p>
              <EvidenceList evidence={a.evidence} />
            </li>
          ))}
        </ul>
      )}
    </Part>
  );
}

/** Only verified relationships count as facts; unverified and conflicting ones are leads; rejected ones are not shown. */
function Relationships({ relationships }: { relationships: Relationship[] }) {
  const { t } = useTranslation();
  const confirmed = relationships.filter((r) => r.verification === 'VERIFIED');
  const candidates = relationships.filter((r) => r.verification === 'UNVERIFIED' || r.verification === 'CONFLICTING');
  return (
    <>
      <Part label={t('detail.relationships')} level={LEVEL}>
        {confirmed.length === 0 ? <p className="muted">{t('detail.noVerified')}</p> : <RelList items={confirmed} />}
      </Part>
      {candidates.length > 0 && (
        <Part label={t('detail.candidates')} level={LEVEL}>
          <p className="notice notice-warn">{t('detail.candidatesHint')}</p>
          <RelList items={candidates} />
        </Part>
      )}
    </>
  );
}

function RelList({ items }: { items: Relationship[] }) {
  const { t } = useTranslation();
  return (
    <ul className="plain-list resource-rels">
      {items.map((r, i) => (
        <li key={`${r.kind}-${r.targetTitle}-${i}`}>
          <strong>{t(`relationship.${r.kind}`)}</strong>{' '}
          {r.targetId ? <Link to={resourcePath(r.targetId)}>{r.targetTitle}</Link> : r.targetTitle}{' '}
          <VerificationBadge verification={r.verification} />
          <EvidenceList evidence={r.evidence} />
        </li>
      ))}
    </ul>
  );
}

/** Provenance: every source record behind the resource, when it was read and its content fingerprint. */
function SourceRecords({ detail }: { detail: ResourceDetail }) {
  const { t, i18n } = useTranslation();
  return (
    <Part label={t('detail.sourceRecords')} level={LEVEL}>
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
            {detail.sourceRecords.map((r) => (
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
    </Part>
  );
}
