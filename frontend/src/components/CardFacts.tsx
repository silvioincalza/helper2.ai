import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { Term } from './Term';

interface Fact {
  key: string;
  label: ReactNode;
  /** null = no source stated it. */
  value: string | null;
}

/**
 * A card's facts in two groups, Use and Build, as a `dl` each. [all]: every fact, a value no source stated reads
 * Unknown (the resource page and the preview). Otherwise only the stated ones, at most [limit] (the inline preview).
 */
export function FactList({ card, all = false, limit }: { card: ResultCard; all?: boolean; limit?: number }) {
  const { t } = useTranslation();
  const use: Fact[] = [
    { key: 'pricing', label: t('card.pricing'), value: card.pricing ? t(`pricing.${card.pricing}`) : null },
    { key: 'platforms', label: t('card.platforms'), value: card.platforms.length ? card.platforms.join(', ') : null },
    {
      key: 'useCases', label: t('card.useCases'),
      value: card.useCases.length ? card.useCases.map((u) => t(`useCase.${u}`, { defaultValue: u })).join(', ') : null,
    },
    { key: 'audience', label: t('card.audience'), value: t(`audience.${card.audience}`) },
  ];
  const build: Fact[] = [
    { key: 'license', label: <Term term="license">{t('card.license')}</Term>, value: card.license ?? null },
    {
      key: 'openSource', label: <Term term="open_source">{t('card.openSource')}</Term>,
      value: card.openSource === true ? t('common.yes') : card.openSource === false ? t('common.no') : null,
    },
    { key: 'techLevel', label: <Term term="tech_level">{t('card.techLevel')}</Term>, value: card.techLevel ? t(`techLevel.${card.techLevel}`) : null },
    { key: 'records', label: t('card.sourceRecords'), value: String(card.sources.length) },
  ];
  const shownUse = (all ? use : use.filter((f) => f.value !== null)).slice(0, limit);
  const shownBuild = (all ? build : build.filter((f) => f.value !== null)).slice(0, limit === undefined ? undefined : limit - shownUse.length);
  const groups = [{ block: 'use', facts: shownUse }, { block: 'build', facts: shownBuild }].filter((g) => g.facts.length > 0);
  return groups.map((g) => (
    <div className="fact-group" data-block={g.block} key={g.block}>
      <p className="fact-group-label">{t(`card.facts.${g.block}`)}</p>
      <dl className="facts">
        {g.facts.map((f) => (
          <div key={f.key}>
            <dt>{f.label}</dt>
            <dd className={f.value === null ? 'not-stated' : undefined}>{f.value ?? t('common.unknown')}</dd>
          </div>
        ))}
      </dl>
    </div>
  ));
}

/** "Works with": derived by disclosed rules from what sources said; the tooltip names the tag, type or format and source. */
export function WorksWith({ card }: { card: ResultCard }) {
  const { t } = useTranslation();
  if (card.compatibleWith.length === 0) return null;
  return (
    <div className="compat">
      <span className="compat-label small">{t('card.compatibleWith')}</span>
      <ul className="compat-list plain-list" aria-label={t('card.compatibleWith')}>
        {card.compatibleWith.map((f) => {
          const why = t(`compat.basis.${f.basis}`, { evidence: f.evidence, source: f.sourceId ?? '' });
          return (
            <li key={f.tool} data-tool={f.tool}>
              <span className="compat-chip" title={why}>{f.label}<span className="sr-only"> ({why})</span></span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
