import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from './Icon';

export type GlossaryKey = 'license' | 'open_source' | 'tech_level' | 'platform';

export function Term({ term, children }: { term: GlossaryKey; children: React.ReactNode }) {
  const { t } = useTranslation();
  const id = useId();
  const [open, setOpen] = useState(false);
  return (
    <span className="term">
      <button
        type="button"
        className="term-btn"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen(!open)}
      >
        <span>{children}</span>
        <span className="term-hint">
          <Icon name="question" />
          <span className="sr-only">{t('glossary.whatIs')}</span>
        </span>
      </button>
      {open && (
        <span id={id} className="term-def" role="note">
          {t(`glossary.${term}.def`)}
        </span>
      )}
    </span>
  );
}
