import './adminText';
import { useState } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { ReviewBulkResult, ReviewItem, ReviewKind } from '@/api/types';
import { AdminDialog } from './AdminDialog';
import { REVIEW_KINDS } from './adminModel';
import { BULK_MAX, NOTE_MAX } from './limits';

export type Decision = 'ACCEPTED' | 'REJECTED';

function countByKind(t: TFunction, items: ReviewItem[]): string {
  const counts = new Map<ReviewKind, number>();
  for (const i of items) counts.set(i.kind, (counts.get(i.kind) ?? 0) + 1);
  return REVIEW_KINDS.filter((k) => counts.has(k))
    .map((k) => t('admin.bulk.kindCount', { count: counts.get(k), kind: t(`admin.kind.${k}`) }))
    .join(' · ');
}

export function bulkSummary(t: TFunction, result: ReviewBulkResult): string {
  const done = result.items.filter((i) => i.outcome === 'RESOLVED').length;
  const failed = result.items.filter((i) => i.outcome !== 'RESOLVED');
  if (failed.length === 0) return t('admin.bulk.allDone', { count: done });
  const reasons = new Map<string, number>();
  for (const f of failed) reasons.set(f.outcome, (reasons.get(f.outcome) ?? 0) + 1);
  const why =
    reasons.size === 1
      ? t(`admin.bulk.reason.${failed[0]!.outcome}`)
      : [...reasons].map(([o, n]) => `${n} ${t(`admin.bulk.reason.${o}`)}`).join(', ');
  return t('admin.bulk.someFailed', { done, failed: failed.length, why });
}

export function BulkBar({
  picked,
  busy,
  onDecide,
  onClear,
}: {
  picked: ReviewItem[];
  busy: boolean;
  onDecide: (decision: Decision, note: string | undefined) => void;
  onClear: () => void;
}) {
  const { t } = useTranslation();
  const [asking, setAsking] = useState<Decision | null>(null);
  const [note, setNote] = useState('');
  const count = picked.length;
  const tooMany = count > BULK_MAX;
  const close = () => setAsking(null);
  const confirm = () => {
    if (!asking) return;
    const decision = asking;
    close();
    onDecide(decision, note.trim() || undefined);
    setNote('');
  };
  const disabled = busy || tooMany || undefined;
  return (
    <div className="h2-admin__bulk" role="group" aria-label={t('admin.bulk.label')}>
      <span>{t('admin.bulk.selected', { count })}</span>
      <button
        type="button"
        className="btn btn-primary"
        aria-disabled={disabled}
        onClick={() => !disabled && setAsking('ACCEPTED')}
      >
        {t('admin.bulk.accept')}
      </button>
      <button
        type="button"
        className="btn"
        aria-disabled={disabled}
        onClick={() => !disabled && setAsking('REJECTED')}
      >
        {t('admin.bulk.reject')}
      </button>
      <button type="button" className="btn" onClick={onClear}>
        {t('admin.bulk.clear')}
      </button>
      {tooMany && <span>{t('admin.bulk.tooMany', { max: BULK_MAX })}</span>}
      <AdminDialog
        open={asking !== null}
        title={t(`admin.bulk.confirmTitle.${asking ?? 'ACCEPTED'}`, { count })}
        onClose={close}
      >
        <p>{countByKind(t, picked)}</p>
        {asking === 'ACCEPTED' && <p className="muted">{t('admin.bulk.acceptRule')}</p>}
        <p className="muted">{t('admin.bulk.final')}</p>
        <label className="h2-admin__note">
          {t('admin.note')}
          <input
            type="text"
            maxLength={NOTE_MAX}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
        <div className="h2-admin__actions">
          <button type="button" className="btn" onClick={close}>
            {t('admin.bulk.cancel')}
          </button>
          <button type="button" className="btn btn-primary" onClick={confirm}>
            {t(`admin.bulk.confirm.${asking ?? 'ACCEPTED'}`, { count })}
          </button>
        </div>
      </AdminDialog>
    </div>
  );
}
