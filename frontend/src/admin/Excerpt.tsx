import './adminText';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

const EXCERPT_SHORT = 280;

// A long text starts shortened, and a button opens it whole. `of` names the text for a screen
// reader when one row holds more than one.
export function Excerpt({ text, of }: { text: string; of?: string }) {
  const { t } = useTranslation();
  const [all, setAll] = useState(false);
  if (text.length <= EXCERPT_SHORT) return <span className="h2-admin__excerpt">{text}</span>;
  return (
    <span className="h2-admin__excerpt">
      {all ? text : `${text.slice(0, EXCERPT_SHORT).trimEnd()}…`}{' '}
      <button
        type="button"
        className="btn-text"
        aria-expanded={all}
        onClick={() => setAll((v) => !v)}
      >
        {t(all ? 'admin.excerpt.less' : 'admin.excerpt.more')}
        {of && <span className="sr-only">, {of}</span>}
      </button>
    </span>
  );
}
