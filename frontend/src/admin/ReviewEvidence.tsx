import './adminText';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import type { ReviewEvidence, ReviewQuote, Verification } from '@/api/types';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { StatusPill, type StatusTone } from '@/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { formatDate, localDay, safeHref } from '@/lib/format';
import { FieldCuration } from './FieldCuration';
import { RelationshipTarget } from './RelationshipTarget';

const VERIFICATION_TONE: Record<Verification, StatusTone> = {
  CONFLICTING: 'warn',
  UNVERIFIED: 'next',
  VERIFIED: 'ok',
  REJECTED: 'later',
};

export function ReviewEvidencePanel({
  itemId,
  onReason,
}: {
  itemId: string;
  onReason: (itemId: string, reason: string) => void;
}) {
  const { t } = useTranslation();
  const evidence = useApi(
    `review-evidence/${itemId}`,
    (s) => adminApi.getReviewEvidence(itemId, s),
    {
      cache: false,
    },
  );
  const shown = evidence.data?.item;
  useEffect(() => {
    if (shown) onReason(shown.id, shown.reason);
  }, [shown, onReason]);
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
        <EvidenceBody evidence={evidence.data} onChanged={evidence.reload} />
      )}
    </section>
  );
}

function EvidenceBody({
  evidence,
  onChanged,
}: {
  evidence: ReviewEvidence;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const { item, run, relationship, fields, validationErrors } = evidence;
  const retargetable = item.status === 'OPEN' && item.kind === 'RELATIONSHIP_CANDIDATE';
  const targetCurated = relationship?.targetOrigin === 'CURATED';
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
              ? formatDate(run.finishedAt, 'INSTANT')
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
          <p className="h2-admin__ai-label">{t('admin.evidence.errorsAi')}</p>
          <ul className="h2-admin__errors" {...aiMark(run?.model)}>
            {validationErrors.map((e, i) => (
              <li key={i}>{e}</li>
            ))}
          </ul>
        </>
      )}
      {relationship && (
        <div className="h2-admin__field">
          <p className="h2-admin__field-head">
            <span {...aiMark(run?.model)}>{t(`relationship.${relationship.kind}`)}</span>{' '}
            <span {...(targetCurated ? {} : aiMark(run?.model))}>{relationship.target}</span>{' '}
            <StatusPill tone={VERIFICATION_TONE[relationship.verification]}>
              {t(`verification.${relationship.verification}`)}
            </StatusPill>
          </p>
          <p className="h2-admin__ai-label">
            {t(`origin.${relationship.origin}`)}
            {targetCurated && <> · {t('admin.evidence.targetCurated')}</>}
          </p>
          {retargetable && (
            <RelationshipTarget
              itemId={item.id}
              currentTitle={relationship.target}
              onChanged={onChanged}
            />
          )}
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
              {item.resourceId && (
                <FieldCuration
                  resourceId={item.resourceId}
                  field={f.field}
                  value={f.value}
                  origin={f.origin}
                  onChanged={onChanged}
                />
              )}
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

function Quotes({ quotes }: { quotes: ReviewQuote[] }) {
  const { t } = useTranslation();
  if (quotes.length === 0) return <p className="muted">{t('admin.evidence.noQuote')}</p>;
  return (
    <ul className="h2-admin__quotes" aria-label={t('admin.evidence.quotes')}>
      {quotes.map((q, i) => {
        const href = safeHref(q.sourceUrl);
        const where = q.sourceId ?? q.sourceUrl;
        return (
          <li key={i}>
            <p className="h2-admin__quote-label">{t('admin.evidence.sourceQuote')}</p>
            {q.excerpt && <blockquote>{q.excerpt}</blockquote>}
            <p className="h2-admin__quote-meta">
              {href ? (
                <a href={href} target="_blank" rel="noopener noreferrer">
                  {where}
                </a>
              ) : (
                where
              )}{' '}
              · {t('detail.retrievedAt', { date: localDay(q.retrievedAt) })}
            </p>
          </li>
        );
      })}
    </ul>
  );
}
