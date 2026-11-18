import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { adminApi } from '@/api/admin';
import type { ReviewResearchDetail, ReviewResearchRun, ReviewResearchStep } from '@/api/types';
import { StatusPill } from '@/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { isActive } from './ReviewResearch';

type Kind = ReviewResearchStep['kind'];
const KINDS: Kind[] = ['MODEL_CALL', 'TOOL_CALL', 'CHECK'];
const UUID = /^[0-9a-f-]{36}$/i;

export function parseEvents(text: string): {
  events: { event: string; data: string }[];
  rest: string;
} {
  const blocks = text.split(/\r?\n\r?\n/);
  const rest = blocks.pop() ?? '';
  const events = blocks
    .map((block) => {
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      return { event, data: data.join('\n') };
    })
    .filter((e) => e.data.length > 0);
  return { events, rest };
}

function merged(steps: ReviewResearchStep[], step: ReviewResearchStep): ReviewResearchStep[] {
  return steps.some((s) => s.seq === step.seq)
    ? steps
    : [...steps, step].sort((a, b) => a.seq - b.seq);
}

async function follow(
  runId: string,
  afterSeq: number,
  signal: AbortSignal,
  onStep: (s: ReviewResearchStep) => void,
  onRun: (r: ReviewResearchRun) => void,
) {
  const res = await adminApi.reviewResearchEvents(runId, afterSeq, signal);
  const reader = res.body?.getReader();
  if (!reader) return;
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    const parsed = parseEvents(buffer + decoder.decode(value, { stream: true }));
    buffer = parsed.rest;
    for (const e of parsed.events) {
      if (e.event === 'step') onStep(JSON.parse(e.data) as ReviewResearchStep);
      if (e.event === 'status') onRun(JSON.parse(e.data) as ReviewResearchRun);
    }
  }
}

export default function ResearchTracePage() {
  const { runId = '' } = useParams();
  const { t } = useTranslation();
  if (!UUID.test(runId)) return <TraceFailed />;
  return <Trace key={runId} runId={runId} t={t} />;
}

function TraceFailed() {
  const { t } = useTranslation();
  return (
    <section className="h2-admin__part" aria-labelledby="rt-h">
      <h2 id="rt-h">{t('admin.research.trace.title')}</h2>
      <p role="alert">{t('admin.research.loadFailed')}</p>
      <Link to="/admin/review">{t('admin.research.trace.back')}</Link>
    </section>
  );
}

function Trace({ runId, t }: { runId: string; t: ReturnType<typeof useTranslation>['t'] }) {
  const loaded = useApi(
    `review-research-trace/${runId}`,
    (signal) => adminApi.getReviewResearch(runId, signal),
    { cache: false },
  );
  const detail: ReviewResearchDetail | undefined = loaded.data;
  const [streamed, setStreamed] = useState<ReviewResearchStep[]>([]);
  const [live, setLive] = useState<ReviewResearchRun | null>(null);
  const [shown, setShown] = useState<Kind | 'ERRORS' | null>(null);
  const [open, setOpen] = useState<ReadonlySet<number>>(new Set());
  const reload = loaded.reload;

  useEffect(() => {
    if (!detail || !isActive(detail.run)) return;
    const ctrl = new AbortController();
    const last = detail.steps.at(-1)?.seq ?? 0;
    follow(
      runId,
      last,
      ctrl.signal,
      (s) => setStreamed((all) => merged(all, s)),
      (r) => {
        setLive(r);
        if (!isActive(r)) reload();
      },
    ).catch(() => undefined);
    return () => ctrl.abort();
  }, [detail, runId, reload]);

  if (loaded.error !== undefined) return <TraceFailed />;
  if (!detail) return <p role="status">{t('admin.research.loading')}</p>;

  const run = live && isActive(detail.run) ? live : detail.run;
  const steps = streamed.reduce(merged, detail.steps);

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
      <Link to="/admin/review">{t('admin.research.trace.back')}</Link>
      <h2 id="rt-h">{t('admin.research.trace.title')}</h2>
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
            tokens: tokens.toLocaleString('en'),
          })}
        </span>
      </p>
      <p aria-live="polite" className="sr-only">
        {isActive(run) ? t('admin.research.trace.live', { count: steps.length }) : ''}
      </p>
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
        {visible.map((s) => {
          const expanded = open.has(s.seq);
          return (
            <li key={s.seq} className="h2-admin__trace-step">
              <span className="h2-admin__mono">{s.seq}</span>
              <div>
                <div className="h2-admin__trace-step-head">
                  <StatusPill
                    tone={s.kind === 'CHECK' ? 'ok' : s.kind === 'TOOL_CALL' ? 'next' : 'later'}
                  >
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
                    onClick={() => toggle(s.seq)}
                  >
                    {t(expanded ? 'admin.research.trace.less' : 'admin.research.trace.more')}{' '}
                    <span className="sr-only">{s.seq}</span>
                  </button>
                </div>
                {s.error && <p className="h2-admin__research-error">{s.error}</p>}
                <div className="h2-admin__trace-io">
                  <div>
                    <p className="h2-admin__trace-label">{t('admin.research.trace.request')}</p>
                    <pre
                      className={
                        expanded
                          ? 'h2-admin__trace-pre h2-admin__trace-pre--open'
                          : 'h2-admin__trace-pre'
                      }
                    >
                      {s.request}
                    </pre>
                  </div>
                  <div>
                    <p className="h2-admin__trace-label">{t('admin.research.trace.response')}</p>
                    <pre
                      className={
                        expanded
                          ? 'h2-admin__trace-pre h2-admin__trace-pre--open'
                          : 'h2-admin__trace-pre'
                      }
                    >
                      {s.response ?? ''}
                    </pre>
                  </div>
                </div>
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
