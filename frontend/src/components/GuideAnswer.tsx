import { useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { GuideResponse } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { Icon } from '@/components/Icon';
import { typeTagClass } from '@/lib/typeTag';

type Recommendation = GuideResponse['recommendations'][number];

/**
 * Where [quote] sits inside [text], matched like the validator does (case and runs of whitespace ignored).
 * Null when it is not there (e.g. the quote came from the title), so the caller shows the quote alone.
 */
export function locateQuote(text: string, quote: string): { before: string; match: string; after: string } | null {
  const words = quote.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return null;
  const pattern = new RegExp(words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s+'), 'i');
  const m = pattern.exec(text);
  if (!m) return null;
  return { before: text.slice(0, m.index), match: m[0], after: text.slice(m.index + m[0].length) };
}

/**
 * The AI guide's answer, proof first: how the answer was made (from the response's own counts), then the
 * picks. Each AI pick shows its checked quote highlighted inside the resource's own text, the user's
 * requirements as confirmed / not confirmed, and the model's explanation folded away and labelled with the outcome of
 * the reason check (checked by a second model call, removed, or not checked).
 */
export function GuideAnswer({ answer }: { answer: GuideResponse }) {
  const { t } = useTranslation();
  const ok = answer.status === 'OK';
  // The model ran but no pick survived (it chose none, or all were rejected): say so plainly.
  const noAiPick = answer.model != null && answer.recommendations.length > 0 && !answer.recommendations.some((r) => r.aiReasoned);
  const kept = answer.recommendations.filter((r) => r.aiReasoned).length;
  const rejected = answer.rejected ?? 0;
  const sourcesOk = answer.sources.filter((s) => s.outcome === 'SUCCEEDED').length;
  // Over the daily quota the model is skipped on purpose: say that, not "the model is not available".
  const dailyQuota = answer.notices.some((n) => n.code === 'GUIDE_DAILY_QUOTA');
  const showStatus = !(dailyQuota && answer.status === 'LLM_UNAVAILABLE');
  return (
    <section aria-labelledby="guide-answer-h" className="guide-answer">
      <h2 id="guide-answer-h" className="sr-only">{t('guide.title')}</h2>

      {answer.model ? (
        <ol className="plain-list proof-strip" aria-label={t('guide.howMade')}>
          <li>
            <span className="proof-n">{t('guide.strip.planned')}</span>
            <strong>{t('guide.searchedFor', { q: answer.searchQuery })}</strong>
            <span className="proof-sub">{answer.model.fastModel}</span>
          </li>
          <li>
            <span className="proof-n">{t('guide.strip.found')}</span>
            <strong>{t('guide.considered', { n: answer.candidatesConsidered })}</strong>
            <span className="proof-sub">{t('guide.sourcesAnswered', { ok: sourcesOk, all: answer.sources.length })}</span>
          </li>
          <li>
            <span className="proof-n">{t('guide.strip.chose')}</span>
            <strong>{t('guide.proposed', { count: kept + rejected })}</strong>
            <span className="proof-sub">{answer.model.reasoningModel}</span>
          </li>
          <li className={kept > 0 ? 'is-checked' : undefined}>
            <span className="proof-n">{t('guide.strip.checked')}</span>
            <strong>{kept > 0 && <Icon name="check" />}{t('guide.kept', { count: kept })}</strong>
            <span className="proof-sub">{rejected > 0 ? t('guide.rejected', { count: rejected }) : t('guide.noneRejected')}</span>
          </li>
        </ol>
      ) : (
        <p className="muted small">{t('guide.searchedFor', { q: answer.searchQuery })} · {t('guide.considered', { n: answer.candidatesConsidered })}</p>
      )}

      {dailyQuota && (
        <p className="notice notice-warn">
          <Icon name="info" /> {t('guide.notice.GUIDE_DAILY_QUOTA')}
        </p>
      )}

      {showStatus && (
        <p className={`notice ${ok ? '' : 'notice-warn'}`}>
          <Icon name={ok && !noAiPick ? 'check' : answer.status === 'PARTIAL' || noAiPick ? 'info' : 'warn'} />{' '}
          {noAiPick ? t('guide.noFit') : t(`guide.status.${answer.status}`)}
        </p>
      )}

      {(answer.constraints?.length ?? 0) > 0 && (
        <p className="asked-for">
          <span className="muted">{t('guide.askedFor')}</span>
          {answer.constraints!.map((c) => <span key={c} className="asked-chip">{c}</span>)}
        </p>
      )}

      <ol className="plain-list guide-recs">
        {answer.recommendations.map((r, i) => (
          <Pick key={r.resourceId} r={r} best={i === 0 && r.aiReasoned} />
        ))}
      </ol>

      {answer.prompt && <PromptBox text={answer.prompt.text} basedOn={answer.prompt.basedOn} />}

      {answer.model && (
        <p className="muted small guide-model">
          {t('guide.answeredBy', { reasoning: answer.model.reasoningModel, fast: answer.model.fastModel, provider: answer.model.provider })}
        </p>
      )}
    </section>
  );
}

function Pick({ r, best }: { r: Recommendation; best: boolean }) {
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
      <h3 className="card-title"><Link to={`/resource/${r.resourceId}`}>{r.title}</Link></h3>
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
        <ul className="plain-list guide-checks" aria-label={t('guide.checksLabel')}>
          {r.checks!.map((c) => (
            <li key={c.constraint} className={c.status === 'CONFIRMED' ? 'check-ok' : 'check-unknown'}>
              {/* Status in words, never colour alone (WCAG 1.4.1). */}
              <Icon name={c.status === 'CONFIRMED' ? 'check' : 'info'} />{' '}
              <strong>{c.constraint}</strong>:{' '}
              {c.status === 'CONFIRMED'
                ? <>{t('guide.checkConfirmed')} <q>{c.quote}</q></>
                : t('guide.checkNotConfirmed')}
            </li>
          ))}
        </ul>
      )}
      {/* Without an AI pick the backend reason is a fixed fallback; the badge already says it. */}
      {r.aiReasoned && r.reasonCheck === 'REMOVED' && (
        <p className="guide-reason muted small"><Icon name="info" /> {t('guide.reasonRemoved')}</p>
      )}
      {r.aiReasoned && r.reasonCheck !== 'REMOVED' && r.reason && (
        <details className="guide-reason" open>
          <summary>{t(r.reasonCheck === 'SUPPORTED' ? 'guide.reasonSupported' : 'guide.reasonLabel')}</summary>
          <p>{r.reason}</p>
        </details>
      )}
      <div className="card-actions">
        <ExternalLink url={r.url} />
        <span className="muted small">{t('guide.sources')}: {r.sourceIds.join(', ')}</span>
      </div>
    </li>
  );
}

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
      <pre className="guide-prompt-text">{text}</pre>
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
