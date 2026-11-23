import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import type { ReviewItem, ReviewResearchRun } from '@/api/types';
import { StatusPill } from '@/ui/StatusPill';
import { REVIEW_TONE } from './adminModel';
import { NOTE_MAX } from './limits';
import { ResearchDecisions, type ResearchOutcome } from './ResearchDecisions';
import type { Decision } from './ReviewBulk';
import { ReviewEvidencePanel } from './ReviewEvidence';
import { ItemDetails } from './ReviewItemDetails';
import { ResearchRunLine, researchable } from './ReviewResearch';

export const DETAIL_ID = 'rq-detail';

interface DecideBarProps {
  reason: string;
  researched: ResearchOutcome | null;
  blocked: boolean;
  busy: boolean;
  note: string;
  barRef: Ref<HTMLDivElement>;
  noteRef: Ref<HTMLInputElement>;
  acceptRef: Ref<HTMLButtonElement>;
  onNote: (text: string) => void;
  onLeaveNote: () => void;
  onDecide: (decision: Decision) => void;
}

export function ReviewDetail({
  item,
  run,
  boxRef,
  onChanged,
  onOutcome,
  onResearchDone,
  onReason,
  ...decide
}: DecideBarProps & {
  item: ReviewItem;
  run: ReviewResearchRun | undefined;
  boxRef: Ref<HTMLElement>;
  onChanged: () => void;
  onOutcome: (outcome: ResearchOutcome | null) => void;
  onResearchDone: () => void;
  onReason: (id: string, reason: string) => void;
}) {
  const { t } = useTranslation();
  const decidable = run?.status === 'DONE' && researchable(item);
  return (
    <section ref={boxRef} id={DETAIL_ID} aria-labelledby="rq-detail-h" className="h2-admin__detail">
      <h3 id="rq-detail-h" className="h2-admin__reason">
        {decide.reason}
      </h3>
      <p className="muted">
        {t(`admin.kind.${item.kind}`)}
        {item.resourceTitle ? ` · ${item.resourceTitle}` : ''}
      </p>
      <ItemDetails item={item} onChanged={onChanged} />
      {run && decidable && (
        <ResearchDecisions key={run.id} run={run} onOutcome={onOutcome} onDone={onResearchDone} />
      )}
      {run && !decidable && <ResearchRunLine run={run} />}
      {item.status === 'OPEN' ? (
        <DecideBar {...decide} />
      ) : (
        <p>
          <StatusPill tone={REVIEW_TONE[item.status]}>
            {t(`admin.reviewStatus.${item.status}`)}
          </StatusPill>
        </p>
      )}
      <ReviewEvidencePanel itemId={item.id} onReason={onReason} />
    </section>
  );
}

function DecideBar({
  reason,
  researched,
  blocked,
  busy,
  note,
  barRef,
  noteRef,
  acceptRef,
  onNote,
  onLeaveNote,
  onDecide,
}: DecideBarProps) {
  const { t } = useTranslation();
  const disabled = busy || undefined;
  return (
    <div ref={barRef} className="h2-admin__decide">
      <label className="h2-admin__note">
        {t('admin.note')}
        <input
          ref={noteRef}
          type="text"
          maxLength={NOTE_MAX}
          aria-keyshortcuts="n"
          value={note}
          onChange={(e) => onNote(e.target.value)}
          onKeyDown={(e) => {
            if (e.key !== 'Escape') return;
            e.preventDefault();
            onLeaveNote();
          }}
        />
      </label>
      <div className="h2-admin__actions">
        <button
          ref={acceptRef}
          type="button"
          className="btn btn-primary"
          aria-disabled={disabled || blocked || undefined}
          aria-describedby={blocked ? 'rq-accept-why' : undefined}
          aria-keyshortcuts="a"
          onClick={() => onDecide('ACCEPTED')}
        >
          {t(researched ? 'admin.research.accept' : 'admin.accept')}
          <span className="sr-only"> {reason}</span>
        </button>
        <button
          type="button"
          className="btn"
          aria-disabled={disabled}
          aria-keyshortcuts="r"
          onClick={() => onDecide('REJECTED')}
        >
          {t('admin.reject')}
          <span className="sr-only"> {reason}</span>
        </button>
        {blocked && researched && (
          <span id="rq-accept-why" className="h2-admin__research-why">
            {t('admin.research.acceptWhy', { count: researched.left.length })}
          </span>
        )}
      </div>
    </div>
  );
}
