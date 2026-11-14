import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { Assertion, ResourceDetail } from '@/api/types';
import { ModeBadge, OriginLabel, VerificationBadge } from '@/ui/Badges';
import { ExternalLink } from '@/ui/ExternalLink';
import { Icon } from '@/ui/Icon';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { formatDate } from '@/lib/format';
import { EvidenceList } from './EvidenceList';
import { FieldStates } from './FieldStates';
import { fieldWord, isField } from './fieldState';

const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

function plainName(field: string): string {
  const words = field
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_.-]+/g, ' ')
    .trim()
    .toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function FactValue({ fact }: { fact: Assertion }) {
  const { t } = useTranslation();
  if (INSTANT.test(fact.value))
    return <time dateTime={fact.value}>{formatDate(fact.value, 'INSTANT')}</time>;
  if (DAY.test(fact.value))
    return <time dateTime={fact.value}>{formatDate(fact.value, 'DAY')}</time>;
  return <>{isField(fact.field) ? fieldWord(t, fact.field, fact.value) : fact.value}</>;
}

function Sources({ card }: { card: ResourceDetail['card'] }) {
  const { t } = useTranslation();
  if (card.sources.length === 0) return null;
  return (
    <>
      <ul className="plain-list h2-res__sources">
        {card.sources.map((s) => (
          <li key={s.sourceId}>
            <strong>{s.sourceName}</strong> <ModeBadge mode={s.mode} /> ·{' '}
            {t('resource.readAt', { date: formatDate(s.retrievedAt, 'INSTANT') })}
          </li>
        ))}
      </ul>
      <p>
        <Link to="/sources">{t('resource.allSources')}</Link>
      </p>
    </>
  );
}

function Facts({ facts }: { facts: Assertion[] }) {
  const { t } = useTranslation();
  if (facts.length === 0) return <p className="muted">{t('detail.noAssertions')}</p>;
  return (
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
          {facts.map((a, i) => (
            <tr key={`${a.field}-${i}`}>
              <th scope="row">
                {t(`detail.field.${a.field}`, { defaultValue: plainName(a.field) })}
              </th>
              <td>
                <span {...(isAiOrigin(a.origin) ? aiMark() : {})}>
                  <FactValue fact={a} />
                </span>
              </td>
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
  );
}

function Records({ records }: { records: ResourceDetail['sourceRecords'] }) {
  const { t } = useTranslation();
  return (
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
          {records.map((r) => (
            <tr key={`${r.sourceId}-${r.externalId}`}>
              <th scope="row">{r.sourceId}</th>
              <td>
                <ExternalLink url={r.url} showHost={false}>
                  {r.externalId}
                </ExternalLink>
              </td>
              <td>
                <time dateTime={r.retrievedAt}>{formatDate(r.retrievedAt, 'INSTANT')}</time>
              </td>
              <td>
                <code title={r.fingerprint}>{r.fingerprint.slice(0, 12)}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const SAYS_NO_SUMMARY: ReadonlySet<ResourceDetail['enrichmentStatus']> = new Set([
  'NOT_RUN',
  'LLM_UNAVAILABLE',
]);

function NoSummary({ status }: { status: ResourceDetail['enrichmentStatus'] }) {
  const { t } = useTranslation();
  const why = t(`enrichment.${status}`);
  return <>{SAYS_NO_SUMMARY.has(status) ? why : `${t('detail.noSummary')}. ${why}`}</>;
}

export function WhereFrom({ detail }: { detail: ResourceDetail }) {
  const { t } = useTranslation();
  return (
    <details className="h2-res__where">
      <summary className="h2-res__fold">
        {t('resource.whereFrom')} <Icon name="chevron" />
      </summary>
      <div className="h2-res__proof">
        <Sources card={detail.card} />
        <section aria-labelledby="fields-h" className="panel">
          <h2 id="fields-h">{t('detail.fields')}</h2>
          <FieldStates detail={detail} />
        </section>
        <section aria-labelledby="as-h" className="panel">
          <h2 id="as-h">{t('detail.assertions')}</h2>
          <Facts facts={detail.assertions} />
        </section>
        <section aria-labelledby="rec-h" className="panel">
          <h2 id="rec-h">{t('detail.sourceRecords')}</h2>
          <Records records={detail.sourceRecords} />
        </section>
        {!detail.summary && (
          <p className="h2-res__note">
            <NoSummary status={detail.enrichmentStatus} />
          </p>
        )}
      </div>
    </details>
  );
}
