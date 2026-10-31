import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { aiMark } from '@/lib/aiMark';
import { resourcePath } from '@/lib/searchState';
import { CopyButton } from '@/components/ui/CopyButton';

/** The AI-written prompt the user can copy into their own tool, with the resources it is based on. */
export function PromptBox({ text, basedOn }: { text: string; basedOn: { resourceId: string; title: string }[] }) {
  const { t } = useTranslation();
  return (
    <section className="guide-prompt" aria-labelledby="guide-prompt-h">
      <div className="guide-prompt-head">
        <h3 id="guide-prompt-h">{t('guide.promptTitle')} <span className="badge">{t('guide.promptAi')}</span></h3>
        <CopyButton text={text} label={t('guide.copy')} primary />
      </div>
      <pre className="guide-prompt-text" {...aiMark()}>{text}</pre>
      {basedOn.length > 0 && (
        <p className="small guide-prompt-based">
          {t('guide.basedOn')}{' '}
          {basedOn.map((b, i) => (
            <span key={b.resourceId}>{i > 0 && ', '}<Link to={resourcePath(b.resourceId)}>{b.title}</Link></span>
          ))}
        </p>
      )}
      <p className="small guide-prompt-based">{t('guide.promptReview')}</p>
    </section>
  );
}
