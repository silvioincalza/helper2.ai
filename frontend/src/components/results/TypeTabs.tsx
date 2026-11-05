import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { FacetValue } from '@/api/types';

/** Types shown as tabs; the others go under More. */
const SHOWN = 4;

/**
 * The type tabs over the list (ADR 0042, v4-2): All, the four types with the most results, then More with the rest.
 * A type chosen under More shows as a tab too, so the pressed tab is always in sight. Counts are the facet counts
 * from the API. Escape closes More and gives focus back to it.
 */
export function TypeTabs({
  total,
  types,
  active,
  onType,
}: {
  total: number;
  types: readonly FacetValue[];
  active: string | null;
  onType: (type: string | null) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const moreId = useId();
  const moreButton = useRef<HTMLButtonElement>(null);
  const byCount = [...types].sort((a, b) => b.count - a.count);
  const shown = byCount.filter((f, i) => i < SHOWN || f.value === active);
  const more = byCount.filter((f) => !shown.includes(f));
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      moreButton.current?.focus();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);
  const choose = (type: string | null) => {
    setOpen(false);
    onType(type);
  };
  const tab = (value: string | null, label: string, count: number) => (
    <button
      key={value ?? ''}
      type="button"
      className="h2-type-tab"
      aria-pressed={active === value}
      onClick={() => choose(value)}
    >
      {label} <span className="h2-type-tab__n">{count}</span>
    </button>
  );
  const label = (f: FacetValue) => t(`type.${f.value}`, { defaultValue: f.value });
  return (
    <div className="h2-type-tabs" role="group" aria-label={t('results.types')}>
      {tab(null, t('results.allTypes'), total)}
      {shown.map((f) => tab(f.value, label(f), f.count))}
      {more.length > 0 && (
        <div className="h2-type-more">
          <button
            ref={moreButton}
            type="button"
            className="h2-type-tab"
            aria-expanded={open}
            aria-controls={moreId}
            onClick={() => setOpen((o) => !o)}
          >
            {t('results.moreTypes')} <span aria-hidden="true">▾</span>
          </button>
          <ul id={moreId} className="plain-list h2-type-more__list" hidden={!open}>
            {more.map((f) => (
              <li key={f.value}>{tab(f.value, label(f), f.count)}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
