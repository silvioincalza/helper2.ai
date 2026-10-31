import { type Ref, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { Icon } from '@/components/Icon';

/** Things the guide can pick for a need. Papers, datasets, prompts and events get no "Guide me". */
const GUIDED: ReadonlySet<ResultCard['type']> = new Set(['MCP_SERVER', 'MODEL', 'LIBRARY', 'AGENT', 'SKILL', 'PRODUCT']);

export function canGuide(card: ResultCard): boolean {
  return GUIDED.has(card.type);
}

/** Open state of one tool's guide. */
export function useGuideToggle() {
  const [open, setOpen] = useState(false);
  return { open, toggle: () => setOpen((o) => !o) };
}

/** The "Guide me" button, named with the tool's title; [bodyId] is the panel it opens. */
export function GuideMeButton({ title, open, onToggle, bodyId, buttonRef }: {
  title: string; open: boolean; onToggle: () => void; bodyId: string; buttonRef?: Ref<HTMLButtonElement>;
}) {
  const { t } = useTranslation();
  const name = { title, interpolation: { escapeValue: false } };
  return (
    <button ref={buttonRef} type="button" className="btn btn-ghost guide-me" aria-expanded={open}
      aria-controls={bodyId} aria-label={t('guideMe.aria', name)} onClick={onToggle}>
      <Icon name="sparkle" />{t('guideMe.label')}
    </button>
  );
}
