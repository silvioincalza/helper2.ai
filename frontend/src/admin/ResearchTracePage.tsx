import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useParams } from 'react-router';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { ReviewResearchDetail, ReviewResearchRun, ReviewResearchStep } from '@/api/types';
import { aiMarkWhen } from '@/lib/aiMark';
import { BackLink } from '@/ui/BackLink';
import { RetryButton } from '@/ui/StatePanel';
import { StatusPill } from '@/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { formatCount } from '@/lib/format';
import { isSameSitePath } from '@/lib/sameSitePath';
import { isActive } from './ReviewResearch';
import { Loading, Skeleton, SkeletonBar, SkeletonRows } from '@/ui/Skeleton';

type Kind = ReviewResearchStep['kind'];
type Translate = ReturnType<typeof useTranslation>['t'];
const KINDS: Kind[] = ['MODEL_CALL', 'TOOL_CALL', 'CHECK'];
const UUID = /^[0-9a-f-]{36}$/i;
const QUEUE = '/admin/review';

function merged(steps: ReviewResearchStep[], step: ReviewResearchStep): ReviewResearchStep[] {
  return steps.some((s) => s.seq === step.seq)
    ? steps
    : [...steps, step].sort((a, b) => a.seq - b.seq);
}

export function restarted(before: ReviewResearchRun, now: ReviewResearchRun): boolean {
  if (now.status === 'QUEUED') return before.status !== 'QUEUED';
  return !!before.startedAt && !!now.startedAt && before.startedAt !== now.startedAt;
}

type Streamed = { attempt: string | null; steps: ReviewResearchStep[] };

export default function ResearchTracePage() {
  const { runId = '' } = useParams();
  const { t } = useTranslation();
  if (!UUID.test(runId)) return <TraceFailed gone />;
  return <Trace key={runId} runId={runId} t={t} />;
}

function queueOf(state: unknown): string | undefined {
  const from = (state as { from?: unknown } | null)?.from;
  return typeof from === 'string' && from.startsWith(QUEUE) && isSameSitePath(from)
    ? from
    : undefined;
}

function QueueLink() {
  const { t } = useTranslation();
  const from = queueOf(useLocation().state);
  return (
    <BackLink from={from} fallback={QUEUE}>
      {t('admin.research.trace.back')}
    </BackLink>
  );
}

function TraceFailed({ gone, onRetry }: { gone: boolean; onRetry?: () => void }) {
  const { t } = useTranslation();
  return (
    <section className="h2-admin__part" aria-labelledby="rt-h">
      <QueueLink />
      <h2 id="rt-h">{t('admin.research.trace.title')}</h2>
      <p role="alert">{t(gone ? 'admin.research.trace.gone' : 'admin.research.loadFailed')}</p>
      {!gone && onRetry && <RetryButton onClick={onRetry} />}
    </section>
  );
}

function Trace({ runId, t }: { runId: string; t: Translate }) {
  const loaded = useApi(
    `review-research-trace/${runId}`,
    (signal) => adminApi.getReviewResearch(runId, signal),
    { cache: false },
  );
  const detail: ReviewResearchDetail | undefined = loaded.data ?? loaded.previous;
  const [streamed, setStreamed] = useState<Streamed>({ attempt: null, steps: [] });
  const [live, setLive] = useState<ReviewResearchRun | null>(null);
  const [stopped, setStopped] = useState(false);
  const [shown, setShown] = useState<Kind | 'ERRORS' | null>(null);
  const [open, setOpen] = useState<ReadonlySet<number>>(new Set());
  const reload = loaded.reload;
  const lastSeq = useRef(0);
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (!detail || !isActive(detail.run)) return;
    const ctrl = new AbortController();
    const attempt = detail.run.startedAt ?? null;
    let finished = false;
    adminApi
      .reviewResearchEvents(
        runId,
        Math.max(lastSeq.current, detail.steps.at(-1)?.seq ?? 0),
        {
          step: (s) => {
            lastSeq.current = Math.max(lastSeq.current, s.seq);
            setStreamed((all) => ({
              attempt,
              steps: all.attempt === attempt ? merged(all.steps, s) : [s],
            }));
          },
          run: (r) => {
            setLive(r);
            if (restarted(detail.run, r)) lastSeq.current = 0;
            else if (isActive(r)) return;
            finished = true;
            reload();
          },
        },
        ctrl.signal,
      )
      .then((end) => {
        if (end === 'idle' || !finished) setStopped(true);
      })
      .catch(() => {
        if (!ctrl.signal.aborted) setStopped(true);
      });
    return () => ctrl.abort();
  }, [detail, runId, reload]);

  if (loaded.error !== undefined) {
    const gone = loaded.error instanceof ApiError && loaded.error.status === 404;
    return <TraceFailed gone={gone} onRetry={reload} />;
  }
  if (!detail)
    return (
      <section className="h2-admin__part h2-admin__trace">
        <QueueLink />
        <Loading label={t('admin.research.loading')}>
          <Skeleton>
            <SkeletonBar size="heading" width="half" />
            <SkeletonBar size="meta" width="wide" />
            <SkeletonRows rows={8} bars={[{ size: 'meta', width: 'full' }]} ruled />
          </Skeleton>
        </Loading>
      </section>
    );

  const run = live && isActive(detail.run) ? live : detail.run;
  const steps =
    streamed.attempt === (detail.run.startedAt ?? null)
      ? streamed.steps.reduce(merged, detail.steps)
      : detail.steps;
  const reconnect = () => {
    setStopped(false);
    heading.current?.focus();
    reload();
  };

  const visible = steps.filter((s) =>
    shown === null ? true : shown === 'ERRORS' ? !!s.error : s.kind === shown,
  );
  const toggle = (seq: number) =>
    setOpen((o) => {
      const next = new Set(o);
      if (!next.delete(seq)) next.add(seq);
      return next;
    });
  const tokens = run.promptTokens + run.completionTokens;

  return (
    <section className="h2-admin__part h2-admin__trace" aria-labelledby="rt-h">
      <QueueLink />
      <h2 id="rt-h" ref={heading} tabIndex={-1}>
        {t('admin.research.trace.title')}
      </h2>
      <p className="h2-admin__trace-meta">
        <StatusPill
          tone={run.status === 'DONE' ? 'ok' : run.status === 'FAILED' ? 'warn' : 'later'}
        >
          {t(`admin.research.status.${run.status}`)}
        </StatusPill>{' '}
        <span className="h2-admin__mono">
          {t('admin.research.trace.meta', {
            model: run.model,
            steps: steps.length,
            tokens: formatCount(tokens, 'en'),
          })}
        </span>
      </p>
      <p aria-live="polite" className="sr-only">
        {isActive(run) && !stopped ? t('admin.research.trace.live', { count: steps.length }) : ''}
      </p>
      {stopped && isActive(run) && (
        <div role="alert" className="banner banner-warn">
          <span>{t('admin.research.trace.stopped')}</span>{' '}
          <button type="button" className="btn btn-sm" onClick={reconnect}>
            {t('admin.research.trace.reconnect')}
          </button>
        </div>
      )}
      {run.error && <p role="alert">{run.error}</p>}
      <p className="h2-admin__research-ai">{t('admin.research.trace.plain')}</p>
      <div className="h2-admin__views" role="group" aria-label={t('admin.research.trace.filter')}>
        <button
          type="button"
          className="btn btn-sm"
          aria-pressed={shown === null}
          onClick={() => setShown(null)}
        >
          {t('admin.research.trace.all')}
        </button>
        {KINDS.map((k) => (
          <button
            key={k}
            type="button"
            className="btn btn-sm"
            aria-pressed={shown === k}
            onClick={() => setShown(k)}
          >
            {t(`admin.research.trace.kind.${k}`)}
          </button>
        ))}
        <button
          type="button"
          className="btn btn-sm"
          aria-pressed={shown === 'ERRORS'}
          onClick={() => setShown('ERRORS')}
        >
          {t('admin.research.trace.errors')}
        </button>
      </div>
      <ol className="h2-admin__trace-steps" aria-label={t('admin.research.trace.stepsLabel')}>
        {visible.map((s) => (
          <TraceStep key={s.seq} step={s} expanded={open.has(s.seq)} onToggle={toggle} t={t} />
        ))}
      </ol>
    </section>
  );
}

function TraceStep({
  step: s,
  expanded,
  onToggle,
  t,
}: {
  step: ReviewResearchStep;
  expanded: boolean;
  onToggle: (seq: number) => void;
  t: Translate;
}) {
  const byModel = s.kind === 'MODEL_CALL';
  const pre = expanded ? 'h2-admin__trace-pre h2-admin__trace-pre--open' : 'h2-admin__trace-pre';
  return (
    <li className="h2-admin__trace-step">
      <span className="h2-admin__mono">{s.seq}</span>
      <div>
        <div className="h2-admin__trace-step-head">
          <StatusPill tone={s.kind === 'CHECK' ? 'ok' : s.kind === 'TOOL_CALL' ? 'next' : 'later'}>
            {t(`admin.research.trace.kind.${s.kind}`)}
          </StatusPill>
          <strong>{s.name}</strong>
          <span className="h2-admin__mono">
            {t('admin.research.trace.took', { ms: s.durationMs })}
            {s.promptTokens != null
              ? ` · ${t('admin.research.trace.tokens', {
                  prompt: s.promptTokens,
                  completion: s.completionTokens ?? 0,
                })}`
              : ''}
          </span>
          <button
            type="button"
            className="btn btn-sm"
            aria-expanded={expanded}
            onClick={() => onToggle(s.seq)}
          >
            {t(expanded ? 'admin.research.trace.less' : 'admin.research.trace.more')}{' '}
            <span className="sr-only">{s.seq}</span>
          </button>
        </div>
        {s.error && <p className="h2-admin__research-error">{s.error}</p>}
        <div className="h2-admin__trace-io">
          <div>
            <p id={`rt-${s.seq}-request`} className="h2-admin__trace-label">
              {t('admin.research.trace.request')}
              <span className="sr-only">{t('admin.research.trace.stepOf', { seq: s.seq })}</span>
            </p>
            <pre
              className={pre}
              role="group"
              aria-labelledby={`rt-${s.seq}-request`}
              // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
              tabIndex={0}
            >
              {s.request}
            </pre>
          </div>
          <div {...aiMarkWhen(byModel, s.name)}>
            <p id={`rt-${s.seq}-response`} className="h2-admin__trace-label">
              {t('admin.research.trace.response')}
              <span className="sr-only">{t('admin.research.trace.stepOf', { seq: s.seq })}</span>
              {byModel && <> · {t('admin.research.trace.byModel')}</>}
            </p>
            <pre
              className={pre}
              role="group"
              aria-labelledby={`rt-${s.seq}-response`}
              // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
              tabIndex={0}
            >
              {s.response ?? ''}
            </pre>
          </div>
        </div>
      </div>
    </li>
  );
}
