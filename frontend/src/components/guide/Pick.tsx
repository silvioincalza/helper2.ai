import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { ExternalLink } from '@/components/ExternalLink';
import { Icon } from '@/components/Icon';
import { aiMark } from '@/lib/aiMark';
import { locateQuote } from '@/lib/locateQuote';
import { typeTagClass } from '@/lib/typeTag';
import type { Recommendation } from './types';
import { resourcePath } from '@/lib/searchState';

/** One recommendation: its checked quote inside the resource's own text, the requirement checks, the model's reason. */
export function Pick({ r, best }: { r: Recommendation; best: boolean }) {
  const { t } = useTranslation();
  const inText = r.aiReasoned && r.description ? locateQuote(r.description, r.quote) : null;
  return (
    <li className={`card guide-pick${best ? ' is-best' : ''}`}>
      <div className="card-badges">
        {best && <span className="best-badge">{t('guide.best')}</span>}
        <span className={typeTagClass(r.type)}>{t(`type.${r.type}`)}</span>
        {/* An AI pick's quote passed the word-for-word validator; a fallback quote is the description itself. */}
        <span className={r.aiReasoned ? 'badge-checked' : 'badge'}>
          {r.aiReasoned && <Icon name="check" />}{r.aiReasoned ? t('guide.quoteChecked') : t('guide.noAi')}
        </span>
      </div>
      <h3 className="card-title"><Link to={resourcePath(r.resourceId)}>{r.title}</Link></h3>
      <figure className="guide-quote">
        <figcaption className="small">
          {r.aiReasoned && <Icon name="check" />}{r.aiReasoned ? t('guide.evidence') : t('guide.fromDescription')}
        </figcaption>
        {inText ? (
          <p className="quote-in-text">{inText.before}<mark>{inText.match}</mark>{inText.after}</p>
        ) : (
          <p className="quote-in-text">“{r.quote}”</p>
        )}
      </figure>
      {(r.checks?.length ?? 0) > 0 && (
        <>
          {/* Art. 50: the planner wrote the requirements; labelled on screen once, marked on each. */}
          <p className="muted small">{t('guide.checksLabel')} <span className="badge">{t('guide.requirementsAi')}</span></p>
          <ul className="plain-list guide-checks" aria-label={t('guide.checksLabel')}>
            {r.checks!.map((c) => (
              <li key={c.constraint} className={c.status === 'CONFIRMED' ? 'check-ok' : 'check-unknown'}>
                {/* Status in words, never colour alone (WCAG 1.4.1). */}
                <Icon name={c.status === 'CONFIRMED' ? 'check' : 'info'} />{' '}
                <strong {...aiMark()}>{c.constraint}</strong>:{' '}
                {c.status === 'CONFIRMED'
                  ? <>{t('guide.checkConfirmed')} <q>{c.quote}</q></>
                  : t('guide.checkNotConfirmed')}
              </li>
            ))}
          </ul>
        </>
      )}
      {/* Without an AI pick the backend reason is a fixed fallback; the badge already says it. */}
      {r.aiReasoned && r.reasonCheck === 'REMOVED' && (
        <p className="guide-reason muted small"><Icon name="info" /> {t('guide.reasonRemoved')}</p>
      )}
      {r.aiReasoned && r.reasonCheck !== 'REMOVED' && r.reason && (
        <details className="guide-reason" open>
          <summary>{t(r.reasonCheck === 'SUPPORTED' ? 'guide.reasonSupported' : 'guide.reasonLabel')}</summary>
          <p {...aiMark()}>{r.reason}</p>
        </details>
      )}
      <div className="card-actions">
        <ExternalLink url={r.url} />
        <span className="muted small">{t('guide.sources')}: {r.sourceIds.join(', ')}</span>
      </div>
    </li>
  );
}
