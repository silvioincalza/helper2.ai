import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { ReviewEvidence, ReviewQuote, Verification } from '@/api/types';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill, type StatusTone } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { formatDate, safeHref } from '@/lib/format';

/** A conflict needs a person most; a verified value least. The tone only repeats the word. */
const VERIFICATION_TONE: Record<Verification, StatusTone> = {
  CONFLICTING: 'warn',
  UNVERIFIED: 'next',
  VERIFIED: 'ok',
  REJECTED: 'later',
};

/**
 * What a review item is about (ADR 0045): the run or the relationship it links to, read from the server for the
 * selected item only. Values a model wrote carry their origin in words ("Extracted by AI") and are marked in the
 * HTML (ADR 0038); quotes come from the sources and are not. Every text is plain React text.
 */
export function ReviewEvidencePanel({ itemId }: { itemId: string }) {
  const { t } = useTranslation();
  const evidence = useApi(`review-evidence/${itemId}`, (s) => api.getReviewEvidence(itemId, s), {
    cache: false,
  });
  return (
    <section aria-labelledby="rq-evidence-h" className="h2-admin__evidence">
      <h4 id="rq-evidence-h" className="h2-admin__h3">
        {t('admin.evidence.title')}
      </h4>
      {evidence.loading ? (
        <p role="status" className="muted">
          {t('admin.evidence.loading')}
        </p>
      ) : evidence.error !== undefined || !evidence.data ? (
        <StatePanel
          tone="error"
          level={5}
          title={t('admin.evidence.loadError')}
          next={<RetryButton onClick={evidence.reload} />}
        />
      ) : (
        <EvidenceBody evidence={evidence.data} />
      )}
    </section>
  );
}

function EvidenceBody({ evidence }: { evidence: ReviewEvidence }) {
  const { t, i18n } = useTranslation();
  const { run, relationship, fields, validationErrors } = evidence;
  if (!run && !relationship) return <p className="muted">{t('admin.evidence.none')}</p>;
  return (
    <>
      {run && (
        <dl className="h2-admin__run">
          <dt>{t('admin.evidence.model')}</dt>
          <dd>{run.model}</dd>
          <dt>{t('admin.evidence.prompt')}</dt>
          <dd>{run.promptVersion}</dd>
          <dt>{t('admin.evidence.outcome')}</dt>
          <dd>{t(`admin.evidence.outcomes.${run.outcome}`)}</dd>
          <dt>{t('admin.evidence.finished')}</dt>
          <dd>
            {run.finishedAt
              ? formatDate(run.finishedAt, i18n.language, 'INSTANT')
              : t('admin.evidence.notFinished')}
          </dd>
          {run.extraEvidence && (
            <>
              <dt>{t('admin.evidence.extra')}</dt>
              <dd>{run.extraEvidence}</dd>
            </>
          )}
        </dl>
      )}
      {validationErrors.length > 0 && (
        <>
          <p className="h2-admin__h3">{t('admin.evidence.errors')}</p>
          <ul className="h2-admin__errors">
            {validationErrors.map((e, i) => (
              <li key={i}>{e}</li>
            ))}
          </ul>
        </>
      )}
      {relationship && (
        <div className="h2-admin__field">
          <p className="h2-admin__field-head">
            <span {...aiMark(run?.model)}>
              {t(`relationship.${relationship.kind}`)} {relationship.target}
            </span>{' '}
            <StatusPill tone={VERIFICATION_TONE[relationship.verification]}>
              {t(`verification.${relationship.verification}`)}
            </StatusPill>
          </p>
          <p className="h2-admin__ai-label">{t(`origin.${relationship.origin}`)}</p>
          <Quotes quotes={relationship.quotes} />
        </div>
      )}
      {fields.length > 0 && (
        <ul className="h2-admin__fields" aria-label={t('admin.evidence.fields')}>
          {fields.map((f, i) => (
            <li key={`${f.field}-${i}`} className="h2-admin__field">
              <p className="h2-admin__field-head">
                <span className="h2-admin__field-name">
                  {t(`detail.field.${f.field}`, { defaultValue: f.field })}
                </span>{' '}
                <span {...(isAiOrigin(f.origin) ? aiMark(run?.model) : {})}>{f.value}</span>{' '}
                <StatusPill tone={VERIFICATION_TONE[f.verification]}>
                  {t(`verification.${f.verification}`)}
                </StatusPill>
              </p>
              <p className="h2-admin__ai-label">{t(`origin.${f.origin}`)}</p>
              <Quotes quotes={f.quotes} />
            </li>
          ))}
        </ul>
      )}
      {!relationship && fields.length === 0 && (
        <p className="muted">{t('admin.evidence.noFields')}</p>
      )}
    </>
  );
}

/** Quotes copied from the sources: plain text, and a link only for an http(s) address. */
function Quotes({ quotes }: { quotes: ReviewQuote[] }) {
  const { t, i18n } = useTranslation();
  if (quotes.length === 0) return <p className="muted">{t('admin.evidence.noQuote')}</p>;
  return (
    <ul className="h2-admin__quotes">
      {quotes.map((q, i) => {
        const href = safeHref(q.sourceUrl);
        const where = q.sourceId ?? q.sourceUrl;
        return (
          <li key={i}>
            {q.excerpt && <blockquote>{q.excerpt}</blockquote>}
            <p className="h2-admin__quote-meta">
              {href ? (
                <a href={href} target="_blank" rel="noopener noreferrer">
                  {where}
                </a>
              ) : (
                where
              )}{' '}
              · {t('detail.retrievedAt', { date: formatDate(q.retrievedAt, i18n.language) })}
            </p>
          </li>
        );
      })}
    </ul>
  );
}
