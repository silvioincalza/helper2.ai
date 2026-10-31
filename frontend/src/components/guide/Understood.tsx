import { useTranslation } from 'react-i18next';
import type { GuideResponse } from '@/api/types';
import { aiMark } from '@/lib/aiMark';
import { readingParts } from './reading';
import type { Refine } from './types';

/**
 * How the guide read the question, as chips: the kind of answer, the named subjects, the period and the
 * requirements. ✕ re-asks without that part (sent as a structured removal, never as text to the model).
 */
export function Understood({ answer, onRefine }: { answer: GuideResponse; onRefine?: Refine | undefined }) {
  const { t } = useTranslation();
  const parts = readingParts(answer, answer.generatedAt.slice(0, 10), t);
  if (parts.length === 0) return null;
  return (
    <p className="understood">
      <span className="muted">{t('guide.understood')}</span>
      {parts.map((p) => (
        <span key={p.key} className="understood-chip">
          {p.ai ? <span {...aiMark()}>{p.label}</span> : p.label}
          {p.drop && onRefine && (
            <button type="button" aria-label={t('guide.remove', { part: p.label })} onClick={() => onRefine({ drop: [p.drop!] })}>×</button>
          )}
        </span>
      ))}
    </p>
  );
}
