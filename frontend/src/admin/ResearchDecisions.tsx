import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router';
import { adminApi } from '@/api/admin';
import type {
  CandidateEdit,
  CandidateType,
  ReviewResearchAttribute,
  ReviewResearchRun,
} from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { formatCount } from '@/lib/format';
import { StatusPill, type StatusTone } from '@/ui/StatusPill';
import { tagsOf } from './CandidateEditForm';
import { useRunDecisions, type Decision } from './researchDecisionsStore';
import { Loading, Skeleton, SkeletonRows } from '@/ui/Skeleton';

const CANDIDATE_TYPES: ReadonlySet<string> = new Set([
  'PRODUCT',
  'AGENT',
  'PROMPT',
  'SKILL',
  'MCP_SERVER',
  'LIBRARY',
  'MODEL',
  'DATASET',
  'ARTICLE',
  'NEWS',
  'EVENT',
  'COURSE',
  'AWESOME_LIST',
]);
const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const PRICING: ReadonlySet<string> = new Set([
  'FREE',
  'FREEMIUM',
  'PAID',
  'OPEN_SOURCE_SELF_HOSTED',
]);
const AUDIENCE: ReadonlySet<string> = new Set(['USE_AI', 'BUILD_WITH_AI', 'BOTH']);
const TECH_LEVEL: ReadonlySet<string> = new Set(['BEGINNER', 'INTERMEDIATE', 'ADVANCED']);
const LICENSE = /^[A-Za-z0-9.+-]{2,40}$/;
const LANGUAGE = /^(simple|[a-z]{2})$/;
const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

const STATUS_TONE: Record<ReviewResearchAttribute['status'], StatusTone> = {
  CONFIRMED: 'ok',
  SINGLE_SOURCE: 'next',
  CONFLICT: 'warn',
  NOT_FOUND: 'later',
};

export interface ResearchOutcome {
  complete: boolean;
  left: string[];
  edit: Omit<CandidateEdit, 'expectedDetails'> | null;
}

type Edit = Omit<CandidateEdit, 'expectedDetails'>;

function changed(a: ReviewResearchAttribute): boolean {
  if (a.status === 'NOT_FOUND' || a.proposed.length === 0) return false;
  return a.proposed.length > 1 || a.proposed[0] !== (a.current ?? '');
}

function slugTags(value: string): string[] | null {
  const tags = tagsOf(value).map((t) => t.replace(/\s+/g, '-'));
  return tags.length > 0 && tags.length <= 12 && tags.every((t) => SLUG.test(t) && t.length <= 40)
    ? tags
    : null;
}

function isDay(value: string): boolean {
  const m = DAY.exec(value);
  if (!m) return false;
  const day = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return day.toISOString().slice(0, 10) === value;
}

function lengthWithin(value: string, max: number): string | null {
  return value.length >= 1 && value.length <= max ? value : null;
}

function oneOf(values: ReadonlySet<string>, value: string): string | null {
  return values.has(value) ? value : null;
}

function matching(pattern: RegExp, value: string): string | null {
  return pattern.test(value) ? value : null;
}

const FIELDS: Record<string, (value: string) => Edit | null> = {
  title: (v) => (lengthWithin(v, 300) ? { title: v } : null),
  type: (v) => (oneOf(CANDIDATE_TYPES, v) ? { type: v as CandidateType } : null),
  tags: (v) => {
    const tags = slugTags(v);
    return tags ? { tags } : null;
  },
  openSource: (v) => (v === 'true' || v === 'false' ? { openSource: v === 'true' } : null),
  canonicalUrl: (v) => (/^https:\/\/\S+$/.test(v) && v.length <= 2000 ? { canonicalUrl: v } : null),
  pricing: (v) => (oneOf(PRICING, v) ? { pricing: v } : null),
  license: (v) => (matching(LICENSE, v) ? { license: v } : null),
  audience: (v) => (oneOf(AUDIENCE, v) ? { audience: v } : null),
  techLevel: (v) => (oneOf(TECH_LEVEL, v) ? { techLevel: v } : null),
  description: (v) => (lengthWithin(v, 4000) ? { description: v } : null),
  language: (v) => (matching(LANGUAGE, v) ? { language: v } : null),
  publishedAt: (v) => (isDay(v) ? { publishedAt: v } : null),
};

export function fieldOf(name: string, value: string): Edit | null {
  const field = FIELDS[name];
  return field ? field(value.trim()) : {};
}

export function outcomeOf(
  attributes: ReviewResearchAttribute[],
  decisions: Readonly<Record<string, Decision>>,
): ResearchOutcome {
  const pending = attributes.filter(changed);
  const left: string[] = [];
  const edit: Edit = {};
  for (const a of pending) {
    const d = decisions[a.name];
    if (d?.kind === 'keep') continue;
    const field = d ? fieldOf(a.name, d.value) : null;
    if (!field) {
      left.push(a.name);
      continue;
    }
    Object.assign(edit, field);
    if (field.description !== undefined) {
      const value = d!.value.trim();
      edit.descriptionWrittenByAi = d!.kind === 'confirm' || a.proposed.includes(value);
    }
  }
  return {
    complete: left.length === 0,
    left,
    edit: Object.keys(edit).length > 0 ? edit : null,
  };
}

type FocusTarget = { to: 'next' } | { to: 'row'; name: string };

const WEB_ADDRESS = /^https?:\/\//i;

function Missing() {
  const { t } = useTranslation();
  return (
    <>
      <span aria-hidden="true">—</span>
      <span className="sr-only">{t('admin.research.none')}</span>
    </>
  );
}

function DuplicateOf({ value }: { value: string }) {
  const { t } = useTranslation();
  return (
    <p>
      {t('admin.research.duplicateOf')}{' '}
      {WEB_ADDRESS.test(value) ? (
        <a
          className="h2-admin__mono"
          href={value}
          target="_blank"
          rel="noopener noreferrer nofollow"
        >
          {value}
        </a>
      ) : (
        <span className="h2-admin__mono">{value}</span>
      )}
    </p>
  );
}

export function ResearchDecisions({
  run,
  onOutcome,
  onDone,
}: {
  run: ReviewResearchRun;
  onOutcome: (outcome: ResearchOutcome | null) => void;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const location = useLocation();
  const loaded = useApi(
    `review-research/${run.id}`,
    (signal) => adminApi.getReviewResearch(run.id, signal),
    { cache: false },
  );
  const detail = loaded.data ?? null;
  const failed = loaded.error !== undefined;
  const [{ decisions, picks }, change] = useRunDecisions(run.id);
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [draftInvalid, setDraftInvalid] = useState(false);
  const [focusTo, setFocusTo] = useState<{ target: FocusTarget; n: number } | null>(null);
  const box = useRef<HTMLDivElement>(null);

  const result = detail?.result ?? null;
  const attributes = useMemo(() => result?.attributes ?? [], [result]);
  const pending = useMemo(() => attributes.filter(changed), [attributes]);
  const next = pending.find((a) => !decisions[a.name]);
  const outcome = useMemo(
    () => (result ? outcomeOf(attributes, decisions) : null),
    [result, attributes, decisions],
  );
  useEffect(() => {
    onOutcome(outcome);
  }, [outcome, onOutcome]);
  useEffect(() => () => onOutcome(null), [onOutcome]);
  const nextName = next?.name;
  useEffect(() => {
    if (!focusTo) return;
    const name = focusTo.target.to === 'row' ? focusTo.target.name : nextName;
    if (!name) {
      onDone();
      return;
    }
    box.current
      ?.querySelector<HTMLElement>(`[data-attr="${CSS.escape(name)}"] [data-first-action]`)
      ?.focus();
  }, [focusTo, nextName, onDone]);

  if (failed) return <p role="alert">{t('admin.research.loadFailed')}</p>;
  if (!detail)
    return (
      <Loading label={t('admin.research.loading')}>
        <Skeleton>
          <SkeletonRows
            rows={4}
            bars={[
              { size: 'meta', width: 'short' },
              { size: 'small', width: 'wide' },
            ]}
            ruled
          />
        </Skeleton>
      </Loading>
    );
  if (!result) return null;

  const moveFocus = (target: FocusTarget) => setFocusTo((f) => ({ target, n: (f?.n ?? 0) + 1 }));
  const setDecisions = (update: (all: Record<string, Decision>) => Record<string, Decision>) =>
    change((all) => ({ ...all, decisions: update({ ...all.decisions }) }));
  const decide = (name: string, d: Decision) => {
    setDecisions((all) => ({ ...all, [name]: d }));
    setEditing(null);
    setDraftInvalid(false);
    moveFocus({ to: 'next' });
  };
  const keepRest = () => {
    setDecisions((all) => {
      for (const a of pending) if (!all[a.name]) all[a.name] = { kind: 'keep' };
      return all;
    });
    setEditing(null);
    moveFocus({ to: 'next' });
  };
  const undo = (name: string) => {
    setDecisions(({ [name]: _, ...rest }) => rest);
    moveFocus({ to: 'row', name });
  };
  const pick = (name: string, value: string) =>
    change((all) => ({ ...all, picks: { ...all.picks, [name]: value } }));
  const confirm = (a: ReviewResearchAttribute) => {
    const value = a.proposed.length > 1 ? picks[a.name] : a.proposed[0];
    if (!value) return;
    if (fieldOf(a.name, value)) {
      decide(a.name, { kind: 'confirm', value });
      return;
    }
    setEditing(a.name);
    setDraft(value);
    setDraftInvalid(true);
  };
  const startEdit = (a: ReviewResearchAttribute) => {
    setEditing(a.name);
    setDraft(picks[a.name] ?? a.proposed[0] ?? a.current ?? '');
    setDraftInvalid(false);
  };
  const saveEdit = (name: string) => {
    const value = draft.trim();
    if (!value) return;
    if (!fieldOf(name, value)) {
      setDraftInvalid(true);
      return;
    }
    decide(name, { kind: 'edit', value });
  };
  const onKeyDown = (e: KeyboardEvent) => {
    const target = e.target as HTMLElement;
    if (
      ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) &&
      target.getAttribute('type') !== 'radio'
    )
      return;
    if (!next || e.ctrlKey || e.metaKey || e.altKey) return;
    const act: Record<string, () => void> = {
      c: () => confirm(next),
      e: () => startEdit(next),
      u: () => decide(next.name, { kind: 'keep' }),
    };
    const action = act[e.key];
    if (!action) return;
    e.preventDefault();
    e.stopPropagation();
    action();
  };
  const left = outcome?.left.length ?? 0;
  const decided = pending.length - left;

  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      ref={box}
      className="h2-admin__research"
      data-ai-generated="true"
      data-ai-model={run.model}
      onKeyDown={onKeyDown}
    >
      <p className="h2-admin__research-ai">
        <strong>{t('admin.research.aiLabel')}</strong> {t('admin.research.aiHint')}
      </p>
      <div className="h2-admin__research-head">
        <StatusPill
          tone={result.verdict === 'KEEP' ? 'ok' : result.verdict === 'REJECT' ? 'next' : 'warn'}
        >
          {t(`admin.research.verdict.${result.verdict}`)}
        </StatusPill>
        <span>{result.reason}</span>
      </div>
      {result.duplicateOf && <DuplicateOf value={result.duplicateOf} />}
      <p className="h2-admin__research-meta">
        {t('admin.research.meta', {
          model: run.model,
          steps: run.steps,
          tokens: formatCount(run.promptTokens + run.completionTokens, 'en'),
        })}{' '}
        <Link
          to={`/admin/review/research/${run.id}`}
          state={{ from: `${location.pathname}${location.search}` }}
        >
          {t('admin.research.everyStep')}
        </Link>
      </p>
      <div className="h2-admin__research-table">
        <table className="h2-table">
          <thead>
            <tr>
              <th scope="col">{t('admin.research.col.value')}</th>
              <th scope="col">{t('admin.research.col.change')}</th>
              <th scope="col">{t('admin.research.col.status')}</th>
              <th scope="col">{t('admin.research.col.decision')}</th>
            </tr>
          </thead>
          <tbody>
            {attributes.map((a) => {
              const isChanged = changed(a);
              const d = decisions[a.name];
              const conflict = a.proposed.length > 1;
              const label = t(`admin.research.attr.${a.name}`, { defaultValue: a.name });
              return (
                <tr
                  key={a.name}
                  data-attr={a.name}
                  className={next?.name === a.name ? 'h2-admin__research-next' : undefined}
                >
                  <th scope="row">{label}</th>
                  <td>
                    {isChanged ? (
                      <>
                        <div className="h2-admin__research-now">
                          <span className="sr-only">{t('admin.research.nowLabel')} </span>
                          {a.current ?? <Missing />}
                        </div>
                        {conflict ? (
                          <fieldset className="h2-admin__research-pick">
                            <legend className="sr-only">
                              {t('admin.research.pick', { name: label })}
                            </legend>
                            {a.proposed.map((v) => (
                              <label key={v}>
                                <input
                                  type="radio"
                                  name={`pick-${a.name}`}
                                  value={v}
                                  checked={picks[a.name] === v}
                                  onChange={() => pick(a.name, v)}
                                />{' '}
                                {v}
                              </label>
                            ))}
                          </fieldset>
                        ) : (
                          <div className="h2-admin__research-proposed">
                            <span className="sr-only">{t('admin.research.proposedLabel')} </span>
                            {a.proposed[0]}
                          </div>
                        )}
                      </>
                    ) : (
                      <div>{a.current ?? a.proposed[0] ?? <Missing />}</div>
                    )}
                    {a.sources.map((s) => (
                      <blockquote
                        key={`${s.evidenceId}-${s.value}`}
                        className="h2-admin__research-quote"
                      >
                        <span className="h2-admin__mono">
                          {s.evidenceId} · {s.tool} · {s.url}
                          {s.authoritative ? ` · ${t('admin.research.authoritative')}` : ''}
                        </span>
                        {s.excerpt}
                      </blockquote>
                    ))}
                  </td>
                  <td>
                    <StatusPill tone={STATUS_TONE[a.status]}>
                      {t(`admin.research.attrStatus.${a.status}`)}
                    </StatusPill>
                  </td>
                  <td>
                    {!isChanged ? (
                      <span className="muted">{t('admin.research.noChange')}</span>
                    ) : d ? (
                      <span className="h2-admin__research-done">
                        <StatusPill tone="ok">{t(`admin.research.decided.${d.kind}`)}</StatusPill>{' '}
                        <button type="button" className="btn btn-sm" onClick={() => undo(a.name)}>
                          {t('admin.research.undo')} <span className="sr-only">{label}</span>
                        </button>
                      </span>
                    ) : editing === a.name ? (
                      <form
                        className="h2-admin__research-edit"
                        onSubmit={(e) => {
                          e.preventDefault();
                          saveEdit(a.name);
                        }}
                      >
                        <label>
                          <span className="sr-only">
                            {t('admin.research.editLabel', { name: label })}
                          </span>
                          {a.name === 'description' ? (
                            <textarea
                              value={draft}
                              maxLength={4000}
                              aria-invalid={draftInvalid || undefined}
                              aria-describedby={
                                draftInvalid ? `research-invalid-${a.name}` : undefined
                              }
                              onChange={(e) => {
                                setDraft(e.target.value);
                                setDraftInvalid(false);
                              }}
                            />
                          ) : (
                            <input
                              type="text"
                              value={draft}
                              maxLength={a.name === 'canonicalUrl' ? 2000 : 300}
                              aria-invalid={draftInvalid || undefined}
                              aria-describedby={
                                draftInvalid ? `research-invalid-${a.name}` : undefined
                              }
                              onChange={(e) => {
                                setDraft(e.target.value);
                                setDraftInvalid(false);
                              }}
                            />
                          )}
                        </label>
                        {draftInvalid && (
                          <p
                            id={`research-invalid-${a.name}`}
                            className="h2-admin__research-error"
                            role="alert"
                          >
                            {t(`admin.research.invalid.${a.name}`, {
                              defaultValue: t('admin.research.invalid.other'),
                            })}
                          </p>
                        )}
                        <button type="submit" className="btn btn-sm btn-primary">
                          {t('admin.research.save')}
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => {
                            setEditing(null);
                            setDraftInvalid(false);
                          }}
                        >
                          {t('admin.research.close')}
                        </button>
                      </form>
                    ) : (
                      <span className="h2-admin__research-actions">
                        <button
                          type="button"
                          className="btn btn-sm btn-primary"
                          data-first-action=""
                          aria-keyshortcuts="c"
                          aria-disabled={(conflict && !picks[a.name]) || undefined}
                          onClick={() => confirm(a)}
                        >
                          {t(conflict ? 'admin.research.confirmPick' : 'admin.research.confirm')}{' '}
                          <span className="sr-only">{label}</span>
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          aria-keyshortcuts="e"
                          onClick={() => startEdit(a)}
                        >
                          {t('admin.research.edit')} <span className="sr-only">{label}</span>
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          aria-keyshortcuts="u"
                          onClick={() => decide(a.name, { kind: 'keep' })}
                        >
                          {t('admin.research.keep')} <span className="sr-only">{label}</span>
                        </button>
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {result.dropped.length > 0 && (
        <details className="h2-admin__research-dropped">
          <summary>{t('admin.research.dropped', { count: result.dropped.length })}</summary>
          <ul>
            {result.dropped.map((d) => (
              <li key={d}>{d}</li>
            ))}
          </ul>
        </details>
      )}
      {pending.length > 0 && (
        <div className="h2-admin__research-progress">
          <span role="status">
            {t('admin.research.progress', { done: decided, count: pending.length })}
          </span>
          <progress
            max={pending.length}
            value={decided}
            aria-label={t('admin.research.progressLabel')}
          />
          {left > 1 && (
            <button type="button" className="btn btn-sm" onClick={keepRest}>
              {t('admin.research.keepRest', { count: left })}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
