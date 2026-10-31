import { ModeBadge } from '@/components/Badges';
import { Icon } from '@/components/Icon';
import type { ElementType } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';

interface Fact { key: string; label: string; value: string | null }

/**
 * Use and Build facts of a result (ADR 0040, 4): a fact no source states says "Not stated", never a guess. A fact the
 * sources disagree on (the merge leaves it empty and records a conflict) says so, with each value and its source.
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
  const conflicts = new Map(card.conflicts.map((c) => [c.field, c.values]));
  // The conflict keeps raw values (enum names, "true"/"false"): word them like the agreed value would be.
  const word = (key: string, value: string) =>
    key === 'pricing' ? t(`pricing.${value}`, { defaultValue: value })
      : key === 'openSource' ? (value === 'true' ? t('common.yes') : value === 'false' ? t('common.no') : value)
        : value;
  const shown = (f: Fact) => {
    const disagree = f.value === null ? conflicts.get(f.key) : undefined;
    if (disagree) {
      return (
        <dd className="is-conflict">
          <Icon name="warn" /> {t('date.conflicting')}: {disagree.map((v) => `${word(f.key, v.value)} (${v.sourceId})`).join(' · ')}
        </dd>
      );
    }
    return <dd className={f.value === null ? 'is-not-stated' : undefined}>{f.value ?? t('resultsV2.notStated')}</dd>;
  };
  const group = (title: string, list: Fact[]) => (
    <div className="h2-facts__group">
      <GroupTag>{title}</GroupTag>
      <dl>
        {list.map((f) => (
          <div key={f.key}><dt>{f.label}</dt>{shown(f)}</div>
        ))}
      </dl>
    </div>
  );
  return <div className="h2-facts">{group(t('resultsV2.use'), use)}{group(t('resultsV2.build'), build)}</div>;
}

/** The sources that list a result, as chips. */
export function ListedBy({ card, stale = new Set<string>() }: { card: ResultCard; stale?: ReadonlySet<string> }) {
  const { t } = useTranslation();
  return (
    <ul className="plain-list inline-list">
      {card.sources.map((s) => (
        <li key={s.sourceId} className="h2-pv__source">
          {s.sourceName} <ModeBadge mode={s.mode} />
          {stale.has(s.sourceId) && <span className="badge served served-stale_cache"><Icon name="warn" />{t('servedFrom.STALE_CACHE')}</span>}
        </li>
      ))}
    </ul>
  );
}
