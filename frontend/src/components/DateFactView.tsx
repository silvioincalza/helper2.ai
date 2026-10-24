import { useTranslation } from 'react-i18next';
import type { DateFact } from '@/api/types';
import { formatDate } from '@/lib/format';
import { Icon } from './Icon';

function fmtAlt(v: string, lang: string): string {
  return /^\d{4}-\d{2}-\d{2}/.test(v) ? formatDate(v, lang, 'DAY') : v;
}

/** Renders a DateFact. UNKNOWN is always the word "Unknown"; never blank, never guessed. */
export function DateFactValue({ fact }: { fact: DateFact }) {
  const { t, i18n } = useTranslation();
  if (fact.status === 'CONFLICTING') {
    // The value shown everywhere else (earliest or latest) first, then what the other sources state.
    const stated = fact.value ? [{ value: fact.value, sourceId: fact.sourceId ?? 'unknown' }] : [];
    const alts = [...stated, ...(fact.alternatives ?? [])];
    return (
      <span className="date-conflict">
        <span className="badge verif verif-conflicting"><Icon name="warn" />{t('date.conflicting')}</span>
        <ul className="plain-list">
          {alts.map((a) => (
            <li key={`${a.sourceId}-${a.value}`}>
              {fmtAlt(a.value, i18n.language)} <span className="muted">({t('date.fromSource', { source: a.sourceId })})</span>
            </li>
          ))}
        </ul>
      </span>
    );
  }
  if (fact.status === 'KNOWN' && fact.value) {
    return (
      <span>
        <time dateTime={fact.value}>{formatDate(fact.value, i18n.language, fact.precision)}</time>
        {fact.sourceId && <span className="muted"> ({t('date.fromSource', { source: fact.sourceId })})</span>}
      </span>
    );
  }
  return <span className="date-unknown">{t('common.unknown')}</span>;
}

export function DateFactRow({ label, fact }: { label: string; fact: DateFact }) {
  return (
    <div className="date-row">
      <dt>{label}</dt>
      <dd><DateFactValue fact={fact} /></dd>
    </div>
  );
}
