import { useTranslation } from 'react-i18next';
import type { Evidence } from '@/api/types';
import { evidenceSource } from '@/lib/evidenceSource';
import { formatDate } from '@/lib/format';
import { ExternalLink } from './ExternalLink';

export function EvidenceList({ evidence }: { evidence: Evidence[] }) {
  const { t, i18n } = useTranslation();
  // A README or model card quote names what it quotes; a source record's link is its own page.
  const evidenceLabel = (url: string) => {
    const source = evidenceSource(url);
    if (source?.kind === 'readme') return t('detail.evidenceReadme', { name: source.name });
    if (source?.kind === 'modelCard') return t('detail.evidenceModelCard', { name: source.name });
    return t('detail.evidenceLink');
  };
  if (evidence.length === 0) return <span className="muted">{t('detail.noEvidence')}</span>;
  return (
    <ul className="plain-list evidence">
      {evidence.map((e) => (
        <li key={e.id}>
          <ExternalLink url={e.sourceUrl}>{evidenceLabel(e.sourceUrl)}</ExternalLink>
          {e.excerpt && <blockquote>{e.excerpt}</blockquote>}
          <span className="muted small">
            {t('detail.retrievedAt', { date: formatDate(e.retrievedAt, i18n.language, 'INSTANT') })}
          </span>
        </li>
      ))}
    </ul>
  );
}
