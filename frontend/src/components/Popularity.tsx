import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { formatCompact, formatDate } from '@/lib/format';

/** Each metric stands alone with its source and date. No combined score, ever. */
export function Popularity({ card }: { card: ResultCard }) {
  const { t, i18n } = useTranslation();
  if (card.popularity.length === 0) return null;
  return (
    <ul className="plain-list popularity" aria-label={t('card.popularity')}>
      {card.popularity.map((m) => {
        const src = card.sources.find((s) => s.sourceId === m.sourceId)?.sourceName ?? m.sourceId;
        return (
          <li key={`${m.metric}-${m.sourceId}`}>
            {t('popularity.line', {
              value: formatCompact(m.value, i18n.language),
              metric: t(`popularity.${m.metric}`, { count: m.value }),
              source: src,
              date: formatDate(m.asOf, i18n.language),
            })}
          </li>
        );
      })}
    </ul>
  );
}
