import { useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { aiMark } from '@/lib/aiMark';

/** The AI-written prompt the user can copy into their own tool, with the resources it is based on. */
export function PromptBox({ text, basedOn }: { text: string; basedOn: { resourceId: string; title: string }[] }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard unavailable: the text is still selectable */
    }
  }
  return (
    <section className="guide-prompt" aria-labelledby="guide-prompt-h">
      <div className="guide-prompt-head">
        <h3 id="guide-prompt-h">{t('guide.promptTitle')} <span className="badge">{t('guide.promptAi')}</span></h3>
        <button type="button" className="btn btn-primary" onClick={() => void copy()}>{copied ? t('guide.copied') : t('guide.copy')}</button>
      </div>
      <pre className="guide-prompt-text" {...aiMark()}>{text}</pre>
      {basedOn.length > 0 && (
        <p className="small guide-prompt-based">
          {t('guide.basedOn')}{' '}
          {basedOn.map((b, i) => (
            <span key={b.resourceId}>{i > 0 && ', '}<Link to={`/resource/${b.resourceId}`}>{b.title}</Link></span>
          ))}
        </p>
      )}
      <p className="small guide-prompt-based">{t('guide.promptReview')}</p>
    </section>
  );
}
