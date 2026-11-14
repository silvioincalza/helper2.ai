import { OriginLabel } from '../../ui/Badges';
import { aiMark } from '@/lib/aiMark';
import { useTranslation } from 'react-i18next';
import type { DateFact } from '@/api/types';
import { formatDate } from '@/lib/format';
import { Icon } from '../../ui/Icon';

function fmtAlt(v: string): string {
  return /^\d{4}-\d{2}-\d{2}/.test(v) ? formatDate(v, 'DAY') : v;
}

function DateFactValue({ fact }: { fact: DateFact }) {
  const { t } = useTranslation();
  if (fact.status === 'CONFLICTING') {
    const stated = fact.value ? [{ value: fact.value, sourceId: fact.sourceId ?? 'unknown' }] : [];
    const alts = [...stated, ...(fact.alternatives ?? [])];
    return (
      <span className="date-conflict">
        <span className="badge verif verif-conflicting">
          <Icon name="warn" />
          {t('date.conflicting')}
        </span>
        <ul className="plain-list">
          {alts.map((a) => (
            <li key={`${a.sourceId}-${a.value}`}>
              {fmtAlt(a.value)}{' '}
              <span className="muted">({t('date.fromSource', { source: a.sourceId })})</span>
            </li>
          ))}
        </ul>
      </span>
    );
  }
  if (fact.status === 'KNOWN' && fact.value) {
    return (
      <span>
        <time dateTime={fact.value}>{formatDate(fact.value, fact.precision)}</time>
        {fact.sourceId && (
          <span className="muted"> ({t('date.fromSource', { source: fact.sourceId })})</span>
        )}
      </span>
    );
  }
  return <span className="date-unknown">{t('common.unknown')}</span>;
}

export function DateFactRow({
  label,
  fact,
  aiRead = false,
}: {
  label: string;
  fact: DateFact;
  aiRead?: boolean;
}) {
  return (
    <div className="date-row">
      <dt>{label}</dt>
      <dd>
        {aiRead ? (
          <>
            <span {...aiMark()}>
              <DateFactValue fact={fact} />
            </span>{' '}
            <OriginLabel origin="AI_EXTRACTED" />
          </>
        ) : (
          <DateFactValue fact={fact} />
        )}
      </dd>
    </div>
  );
}
