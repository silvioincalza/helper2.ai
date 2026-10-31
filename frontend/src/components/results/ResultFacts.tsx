import type { ElementType } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';

interface Fact { key: string; label: string; value: string | null }

/**
 * Use and Build facts of a result (ADR 0040, 4): a fact no source states says "Not stated", never a guess.
 * Shared by the results preview and the resource page; [groupTag] keeps the heading levels right in each.
 */
export function ResultFacts({ card, groupTag: GroupTag }: { card: ResultCard; groupTag: ElementType }) {
  const { t } = useTranslation();
  const use: Fact[] = [
    { key: 'pricing', label: t('card.pricing'), value: card.pricing ? t(`pricing.${card.pricing}`) : null },
    { key: 'platforms', label: t('card.platforms'), value: card.platforms.length ? card.platforms.join(', ') : null },
    { key: 'useCases', label: t('card.useCases'), value: card.useCases.length ? card.useCases.map((u) => t(`useCase.${u}`, { defaultValue: u })).join(', ') : null },
  ];
  const build: Fact[] = [
    { key: 'license', label: t('card.license'), value: card.license ?? null },
    { key: 'openSource', label: t('card.openSource'), value: card.openSource === true ? t('common.yes') : card.openSource === false ? t('common.no') : null },
    { key: 'techLevel', label: t('card.techLevel'), value: card.techLevel ? t(`techLevel.${card.techLevel}`) : null },
  ];
  const group = (title: string, list: Fact[]) => (
    <div className="h2-facts__group">
      <GroupTag>{title}</GroupTag>
      <dl>
        {list.map((f) => (
          <div key={f.key}><dt>{f.label}</dt><dd className={f.value === null ? 'is-not-stated' : undefined}>{f.value ?? t('resultsV2.notStated')}</dd></div>
        ))}
      </dl>
    </div>
  );
  return <div className="h2-facts">{group(t('resultsV2.use'), use)}{group(t('resultsV2.build'), build)}</div>;
}

/** The sources that list a result, as chips. */
export function ListedBy({ card }: { card: ResultCard }) {
  return (
    <ul className="plain-list inline-list">
      {card.sources.map((s) => <li key={s.sourceId} className="h2-pv__source">{s.sourceName}</li>)}
    </ul>
  );
}
