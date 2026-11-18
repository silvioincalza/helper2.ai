import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router';
import { adminApi } from '@/api/admin';
import type { ReviewItem, ReviewResearchRun } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { aiMark, aiModel } from '@/lib/aiMark';
import { StatusPill, type StatusTone } from '@/ui/StatusPill';
import { AdminDialog } from './AdminDialog';
import { NOTE_MAX } from './limits';

const POLL_MS = 2000;
export const RESEARCH_FILTERS = ['KEEP', 'REJECT', 'DUPLICATE', 'ACTIVE', 'NONE'] as const;
const RESEARCHABLE = new Set<ReviewItem['kind']>(['RESOURCE_CANDIDATE', 'COURSE_CANDIDATE']);

export function researchable(item: ReviewItem): boolean {
  return item.status === 'OPEN' && RESEARCHABLE.has(item.kind);
}

function startable(item: ReviewItem, run: ReviewResearchRun | undefined): boolean {
  return researchable(item) && !isActive(run) && run?.status !== 'DONE';
}

function researched(item: ReviewItem, run: ReviewResearchRun | undefined): boolean {
  return researchable(item) && run?.status === 'DONE';
}

function useTraceState(): { from: string } {
  const location = useLocation();
  return { from: `${location.pathname}${location.search}` };
}

export function isActive(run: ReviewResearchRun | undefined): boolean {
  return run?.status === 'QUEUED' || run?.status === 'RUNNING';
}

export function useResearchRuns(itemIds: string[]) {
  const key = itemIds.join(',');
  const [runs, setRuns] = useState<ReadonlyMap<string, ReviewResearchRun>>(new Map());
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((n) => n + 1), []);
  useEffect(() => {
    const ids = key ? key.split(',') : [];
    if (ids.length === 0) return;
    const ctrl = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async () => {
      try {
        const found = await adminApi.listReviewResearch(ids, ctrl.signal);
        const next = new Map(found.runs.map((r) => [r.itemId, r] as const));
        setRuns(next);
        if ([...next.values()].some(isActive)) timer = setTimeout(() => void load(), POLL_MS);
      } catch {
        if (!ctrl.signal.aborted) timer = setTimeout(() => void load(), POLL_MS * 5);
      }
    };
    void load();
    return () => {
      ctrl.abort();
      if (timer) clearTimeout(timer);
    };
  }, [key, tick]);
  const shown = new Map([...runs].filter(([id]) => key.split(',').includes(id)));
  return { runs: shown, reload };
}

const TONE: Record<ReviewResearchRun['status'], StatusTone> = {
  QUEUED: 'later',
  RUNNING: 'later',
  DONE: 'ok',
  FAILED: 'warn',
  CANCELLED: 'later',
};

const VERDICT_TONE: Record<NonNullable<ReviewResearchRun['verdict']>, StatusTone> = {
  KEEP: 'ok',
  REJECT: 'next',
  DUPLICATE: 'warn',
};

export function ResearchCell({
  item,
  run,
  busy,
  onStart,
  onCancel,
}: {
  item: ReviewItem;
  run: ReviewResearchRun | undefined;
  busy: boolean;
  onStart: (itemId: string) => void;
  onCancel: (run: ReviewResearchRun) => void;
}) {
  const { t } = useTranslation();
  const from = useTraceState();
  if (!researchable(item) && !run) return null;
  const start = (key: 'start' | 'again') =>
    researchable(item) ? (
      <button
        type="button"
        className="btn btn-sm"
        aria-disabled={busy || undefined}
        onClick={() => !busy && onStart(item.id)}
      >
        {t(`admin.research.${key}`)} <span className="sr-only">{item.reason}</span>
      </button>
    ) : null;
  if (!run) return start('start');
  const active = isActive(run);
  return (
    <div className="h2-admin__research-cell">
      {run.status === 'DONE' && run.verdict ? (
        <StatusPill tone={VERDICT_TONE[run.verdict]}>
          {t(`admin.research.verdict.${run.verdict}`)}
        </StatusPill>
      ) : (
        <StatusPill tone={TONE[run.status]}>
          {run.status === 'RUNNING'
            ? t('admin.research.running', { steps: run.steps })
            : t(`admin.research.status.${run.status}`)}
        </StatusPill>
      )}
      {run.status === 'FAILED' && run.error && (
        <span className="h2-admin__research-error">{run.error}</span>
      )}
      <Link
        className="h2-admin__research-link"
        to={`/admin/review/research/${run.id}`}
        state={from}
      >
        {active ? t('admin.research.watch') : t('admin.research.steps', { count: run.steps })}
      </Link>
      {active ? (
        <button type="button" className="btn btn-sm" onClick={() => onCancel(run)}>
          {t('admin.research.cancel')}
        </button>
      ) : (
        start(run.status === 'CANCELLED' ? 'start' : 'again')
      )}
    </div>
  );
}

export function ResearchRunLine({ run }: { run: ReviewResearchRun }) {
  const { t } = useTranslation();
  const from = useTraceState();
  return (
    <p className="h2-admin__research-meta">
      {t('admin.research.runLine', { status: t(`admin.research.status.${run.status}`) })}{' '}
      <Link to={`/admin/review/research/${run.id}`} state={from}>
        {isActive(run) ? t('admin.research.watch') : t('admin.research.everyStep')}
      </Link>
    </p>
  );
}

export function ResearchBar({
  picked,
  page,
  runs,
  busy,
  onStart,
  onStartAll,
  onRejectResearched,
}: {
  picked: ReviewItem[];
  page: ReviewItem[];
  runs: ReadonlyMap<string, ReviewResearchRun>;
  busy: boolean;
  onStart: (itemIds: string[]) => void;
  onStartAll: () => void;
  onRejectResearched: (items: ReviewItem[]) => void;
}) {
  const { t } = useTranslation();
  const pickedIds = picked.filter((i) => startable(i, runs.get(i.id))).map((i) => i.id);
  const pageIds = page.filter((i) => startable(i, runs.get(i.id))).map((i) => i.id);
  const done = page.filter((i) => researched(i, runs.get(i.id))).length;
  const rejects = page.filter((i) => {
    const r = runs.get(i.id);
    return researchable(i) && r?.status === 'DONE' && r.verdict === 'REJECT';
  });
  const active = [...runs.values()].filter(isActive).length;
  const disabled = busy || undefined;
  const split = useId();
  return (
    <section className="h2-admin__research-bar" aria-label={t('admin.research.bar')}>
      <span className="h2-admin__research-bar-title">{t('admin.research.bar')}</span>
      <button
        type="button"
        className="btn btn-sm btn-primary"
        aria-disabled={disabled || pickedIds.length === 0 || undefined}
        onClick={() => !busy && pickedIds.length > 0 && onStart(pickedIds)}
      >
        {t('admin.research.selected', { count: pickedIds.length })}
      </button>
      <button
        type="button"
        className="btn btn-sm"
        aria-disabled={disabled || pageIds.length === 0 || undefined}
        aria-describedby={done > 0 ? split : undefined}
        onClick={() => !busy && pageIds.length > 0 && onStart(pageIds)}
      >
        {t('admin.research.page', { count: pageIds.length })}
      </button>
      {done > 0 && (
        <span id={split} className="h2-admin__research-why">
          {t('admin.research.split', { count: pageIds.length, done })}
        </span>
      )}
      <button
        type="button"
        className="btn btn-sm"
        aria-disabled={disabled}
        onClick={() => !busy && onStartAll()}
      >
        {t('admin.research.all')}
      </button>
      {rejects.length > 0 && (
        <button
          type="button"
          className="btn btn-sm"
          aria-disabled={disabled}
          onClick={() => !busy && onRejectResearched(rejects)}
        >
          {t('admin.research.rejectResearched', { count: rejects.length })}
        </button>
      )}
      <span className="h2-admin__research-bar-status" role="status">
        {active > 0 ? t('admin.research.active', { count: active }) : ''}
      </span>
    </section>
  );
}

export function StartAllDialog({
  open,
  busy,
  onClose,
  onConfirm,
}: {
  open: boolean;
  busy: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  return (
    <AdminDialog open={open} title={t('admin.research.allTitle')} onClose={onClose}>
      <StartAllBody busy={busy} onClose={onClose} onConfirm={onConfirm} />
    </AdminDialog>
  );
}

function StartAllBody({
  busy,
  onClose,
  onConfirm,
}: {
  busy: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  const estimate = useApi(
    'review-research-estimate',
    (signal) => adminApi.estimateReviewResearch(signal),
    { cache: false },
  );
  const found = estimate.data;
  return (
    <>
      {found ? (
        <dl className="h2-admin__estimate">
          <dt>{t('admin.research.allItems')}</dt>
          <dd>{found.items.toLocaleString('en')}</dd>
          <dt>{t('admin.research.allCalls')}</dt>
          <dd>{found.maxModelCalls.toLocaleString('en')}</dd>
        </dl>
      ) : (
        <p role="status">
          {estimate.error !== undefined
            ? t('admin.research.estimateFailed')
            : t('admin.research.estimating')}
        </p>
      )}
      <p className="muted">{t('admin.research.allHint')}</p>
      <div className="h2-admin__actions">
        <button
          type="button"
          className="btn btn-primary"
          aria-disabled={busy || !found || found.items === 0 || undefined}
          onClick={() => !busy && found && found.items > 0 && onConfirm()}
        >
          {t('admin.research.allConfirm', {
            count: found?.items ?? 0,
            items: (found?.items ?? 0).toLocaleString('en'),
          })}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          {t('admin.research.close')}
        </button>
      </div>
    </>
  );
}

export function BatchRejectDialog({
  items,
  runs,
  busy,
  onClose,
  onConfirm,
}: {
  items: ReviewItem[];
  runs: ReadonlyMap<string, ReviewResearchRun>;
  busy: boolean;
  onClose: () => void;
  onConfirm: (ids: string[], note: string | undefined) => void;
}) {
  const { t } = useTranslation();
  return (
    <AdminDialog
      open={items.length > 0}
      title={t('admin.research.rejectTitle', { count: items.length })}
      className="h2-admin__dialog--wide"
      onClose={onClose}
    >
      <BatchRejectBody
        key={items.map((i) => i.id).join(',')}
        items={items}
        runs={runs}
        busy={busy}
        onClose={onClose}
        onConfirm={onConfirm}
      />
    </AdminDialog>
  );
}

function BatchRejectBody({
  items,
  runs,
  busy,
  onClose,
  onConfirm,
}: {
  items: ReviewItem[];
  runs: ReadonlyMap<string, ReviewResearchRun>;
  busy: boolean;
  onClose: () => void;
  onConfirm: (ids: string[], note: string | undefined) => void;
}) {
  const { t } = useTranslation();
  const prefix = useId();
  const [unticked, setUnticked] = useState<ReadonlySet<string>>(new Set());
  const [note, setNote] = useState(() => t('admin.research.rejectNote'));
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const runKey = items
    .map((i) => runs.get(i.id)?.id)
    .filter((id): id is string => !!id)
    .join(',');
  useEffect(() => {
    if (!runKey) return;
    const ctrl = new AbortController();
    for (const runId of runKey.split(',')) {
      adminApi
        .getReviewResearch(runId, ctrl.signal)
        .then((d) => {
          const reason = d.result?.reason;
          if (reason) setReasons((r) => ({ ...r, [d.run.itemId]: reason }));
        })
        .catch(() => undefined);
    }
    return () => ctrl.abort();
  }, [runKey]);
  const chosen = items.filter((i) => !unticked.has(i.id)).map((i) => i.id);
  const toggle = (id: string) =>
    setUnticked((u) => {
      const next = new Set(u);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  return (
    <>
      <p>{t('admin.research.rejectLede')}</p>
      <p className="h2-admin__research-ai">
        <strong>{t('admin.research.rejectAi')}</strong>
      </p>
      <ul className="h2-admin__reject-list" {...aiMark()}>
        {items.map((i) => (
          <li key={i.id} {...aiModel(runs.get(i.id)?.model)}>
            <input
              id={`${prefix}-${i.id}`}
              type="checkbox"
              checked={!unticked.has(i.id)}
              onChange={() => toggle(i.id)}
            />
            <label htmlFor={`${prefix}-${i.id}`}>
              <strong>{i.reason}</strong>
              <span className="muted">{reasons[i.id] ?? ''}</span>
            </label>
          </li>
        ))}
      </ul>
      <label className="h2-admin__note">
        {t('admin.research.rejectNoteLabel')}
        <input
          type="text"
          maxLength={NOTE_MAX}
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
      </label>
      <div className="h2-admin__actions">
        <button
          type="button"
          className="btn btn-primary"
          aria-disabled={busy || chosen.length === 0 || undefined}
          onClick={() => !busy && chosen.length > 0 && onConfirm(chosen, note.trim() || undefined)}
        >
          {t('admin.research.rejectConfirm', { count: chosen.length })}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          {t('admin.research.close')}
        </button>
      </div>
    </>
  );
}
