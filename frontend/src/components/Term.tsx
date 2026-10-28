import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from './Icon';

export type GlossaryKey =
  | 'mcp_server' | 'model' | 'agent' | 'prompt' | 'skill' | 'library' | 'dataset'
  | 'product' | 'article' | 'paper' | 'news' | 'event' | 'course' | 'other'
  | 'license' | 'open_source' | 'tech_level' | 'platform' | 'popularity' | 'provenance';

/**
 * Inline definition: the term is a real button (Enter/Space), the definition is revealed in place.
 * [note] is appended to the definition, e.g. where a value on this card comes from.
 */
export function Term({ term, children, note }: { term: GlossaryKey; children?: React.ReactNode; note?: string }) {
  const { t } = useTranslation();
  const id = useId();
  const [open, setOpen] = useState(false);
  return (
    <span className="term">
      <button type="button" className="term-btn" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>
        <span>{children ?? t(`glossary.${term}.term`)}</span>
        <span className="term-hint"><Icon name="question" /><span className="sr-only">{t('glossary.whatIs')}</span></span>
      </button>
      {open && <span id={id} className="term-def" role="note">{t(`glossary.${term}.def`)}{note ? ` ${note}` : ''}</span>}
    </span>
  );
}
