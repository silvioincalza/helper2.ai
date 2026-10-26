import { useTranslation } from 'react-i18next';
import type { Evidence } from '@/api/types';
import { formatDate } from '@/lib/format';
import { ExternalLink } from './ExternalLink';

export function EvidenceList({ evidence }: { evidence: Evidence[] }) {
  const { t, i18n } = useTranslation();
  if (evidence.length === 0) return <span className="muted">{t('detail.noEvidence')}</span>;
  return (
    <ul className="plain-list evidence">
      {evidence.map((e) => (
        <li key={e.id}>
          <ExternalLink url={e.sourceUrl}>{t('detail.evidenceLink')}</ExternalLink>
          {e.excerpt && <blockquote>{e.excerpt}</blockquote>}
          <span className="muted small">{t('detail.retrievedAt', { date: formatDate(e.retrievedAt, i18n.language, 'INSTANT') })}</span>
        </li>
      ))}
    </ul>
  );
}
