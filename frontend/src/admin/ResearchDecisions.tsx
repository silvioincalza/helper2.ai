import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { adminApi } from '@/api/admin';
import type {
  CandidateEdit,
  CandidateType,
  ReviewResearchAttribute,
  ReviewResearchRun,
} from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { StatusPill, type StatusTone } from '@/ui/StatusPill';
import { tagsOf } from './CandidateEditForm';

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
]);
const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const STATUS_TONE: Record<ReviewResearchAttribute['status'], StatusTone> = {
  CONFIRMED: 'ok',
  SINGLE_SOURCE: 'next',
  CONFLICT: 'warn',
  NOT_FOUND: 'later',
};

export type Decision =
  { kind: 'confirm'; value: string } | { kind: 'edit'; value: string } | { kind: 'keep' };

export interface ResearchOutcome {
  complete: boolean;
  left: string[];
  edit: Omit<CandidateEdit, 'expectedDetails'> | null;
  note: string;
}

type Edit = Omit<CandidateEdit, 'expectedDetails'>;

const TEXT_VALUES = [
  'canonicalUrl',
  'pricing',
  'license',
  'audience',
  'techLevel',
  'language',
] as const;

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

export function outcomeOf(
  attributes: ReviewResearchAttribute[],
  decisions: Readonly<Record<string, Decision>>,
  candidateEditable: boolean,
): ResearchOutcome {
  const pending = attributes.filter(changed);
  const left = pending.filter((a) => !decisions[a.name]).map((a) => a.name);
  const edit: Edit = {};
  const noted: string[] = [];
  for (const a of pending) {
    const d = decisions[a.name];
    if (!d || d.kind === 'keep') continue;
    const value = d.value.trim();
    if (candidateEditable) put(edit, a, value, d.kind === 'confirm' || a.proposed.includes(value));
    else noted.push(`${a.name}=${value}`);
  }
  return {
    complete: left.length === 0,
    left,
    edit: Object.keys(edit).length > 0 ? edit : null,
    note: noted.length > 0 ? `Research confirmed: ${noted.join('; ')}` : '',
  };
}

function put(edit: Edit, a: ReviewResearchAttribute, value: string, asProposed: boolean): void {
  if (value.length === 0) return;
  if (a.name === 'title' && value.length <= 300) edit.title = value;
  else if (a.name === 'type' && CANDIDATE_TYPES.has(value)) edit.type = value as CandidateType;
  else if (a.name === 'tags') {
    const tags = slugTags(value);
    if (tags) edit.tags = tags;
  } else if (a.name === 'openSource' && (value === 'true' || value === 'false')) {
    edit.openSource = value === 'true';
  } else if (a.name === 'description') {
    edit.description = value;
    edit.descriptionWrittenByAi = asProposed;
  } else if (a.name === 'publishedAt') edit.publishedAt = value;
  else if ((TEXT_VALUES as readonly string[]).includes(a.name)) {
    (edit as Record<string, unknown>)[a.name] = value;
  }
}

export function ResearchDecisions({
  run,
  candidateEditable,
  onOutcome,
}: {
  run: ReviewResearchRun;
  candidateEditable: boolean;
  onOutcome: (outcome: ResearchOutcome | null) => void;
}) {
  const { t } = useTranslation();
  const loaded = useApi(
    `review-research/${run.id}`,
    (signal) => adminApi.getReviewResearch(run.id, signal),
    { cache: false },
  );
  const detail = loaded.data ?? null;
  const failed = loaded.error !== undefined;
  const [decisions, setDecisions] = useState<Record<string, Decision>>({});
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [moveFocus, setMoveFocus] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (moveFocus === 0) return;
    box.current?.querySelector<HTMLElement>('.h2-admin__research-next button')?.focus();
  }, [moveFocus]);

  const result = detail?.result ?? null;
  const attributes = useMemo(() => result?.attributes ?? [], [result]);
  const outcome = useMemo(
    () => (result ? outcomeOf(attributes, decisions, candidateEditable) : null),
    [result, attributes, decisions, candidateEditable],
  );
  useEffect(() => {
    onOutcome(outcome);
  }, [outcome, onOutcome]);
  useEffect(() => () => onOutcome(null), [onOutcome]);

  if (failed) return <p role="alert">{t('admin.research.loadFailed')}</p>;
  if (!detail) return <p role="status">{t('admin.research.loading')}</p>;
  if (!result) return null;

  const pending = attributes.filter(changed);
  const next = pending.find((a) => !decisions[a.name]);
  const decide = (name: string, d: Decision) => {
    setDecisions((all) => ({ ...all, [name]: d }));
    setEditing(null);
    setMoveFocus((n) => n + 1);
  };
  const confirm = (a: ReviewResearchAttribute) => {
    const value = a.proposed.length > 1 ? picks[a.name] : a.proposed[0];
    if (value) decide(a.name, { kind: 'confirm', value });
  };
  const startEdit = (a: ReviewResearchAttribute) => {
    setEditing(a.name);
    setDraft(picks[a.name] ?? a.proposed[0] ?? a.current ?? '');
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
  const decided = pending.length - (outcome?.left.length ?? 0);

  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      ref={box}
      className="h2-admin__research"
      data-ai-generated="true"
      data-ai-model={run.model}
      onKeyDown={onKeyDown}
    >
      <div className="h2-admin__research-head">
        <StatusPill
          tone={result.verdict === 'KEEP' ? 'ok' : result.verdict === 'REJECT' ? 'next' : 'warn'}
        >
          {t(`admin.research.verdict.${result.verdict}`)}
        </StatusPill>
        <span>{result.reason}</span>
      </div>
      {result.duplicateOf && (
        <p>
          {t('admin.research.duplicateOf')}{' '}
          <span className="h2-admin__mono">{result.duplicateOf}</span>
        </p>
      )}
      <p className="h2-admin__research-meta">
        {t('admin.research.meta', {
          model: run.model,
          steps: run.steps,
          tokens: (run.promptTokens + run.completionTokens).toLocaleString('en'),
        })}{' '}
        <Link to={`/admin/review/research/${run.id}`}>{t('admin.research.everyStep')}</Link>
      </p>
      <p className="h2-admin__research-ai" aria-label={t('admin.research.aiLabel')}>
        <strong>{t('admin.research.aiLabel')}</strong> {t('admin.research.aiHint')}
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
                  className={next?.name === a.name ? 'h2-admin__research-next' : undefined}
                >
                  <th scope="row">
                    {t(`admin.research.attr.${a.name}`, { defaultValue: a.name })}
                  </th>
                  <td>
                    {isChanged ? (
                      <>
                        <div className="h2-admin__research-now">
                          {a.current ?? t('admin.research.none')}
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
                                  onChange={() => setPicks((p) => ({ ...p, [a.name]: v }))}
                                />{' '}
                                {v}
                              </label>
                            ))}
                          </fieldset>
                        ) : (
                          <div className="h2-admin__research-proposed">{a.proposed[0]}</div>
                        )}
                      </>
                    ) : (
                      <div>{a.current ?? a.proposed[0] ?? t('admin.research.none')}</div>
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
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => setDecisions(({ [a.name]: _, ...rest }) => rest)}
                        >
                          {t('admin.research.undo')} <span className="sr-only">{label}</span>
                        </button>
                      </span>
                    ) : editing === a.name ? (
                      <form
                        className="h2-admin__research-edit"
                        onSubmit={(e) => {
                          e.preventDefault();
                          if (draft.trim()) decide(a.name, { kind: 'edit', value: draft.trim() });
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
                              onChange={(e) => setDraft(e.target.value)}
                            />
                          ) : (
                            <input
                              type="text"
                              value={draft}
                              maxLength={300}
                              onChange={(e) => setDraft(e.target.value)}
                            />
                          )}
                        </label>
                        <button type="submit" className="btn btn-sm btn-primary">
                          {t('admin.research.save')}
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => setEditing(null)}
                        >
                          {t('admin.research.close')}
                        </button>
                      </form>
                    ) : (
                      <span className="h2-admin__research-actions">
                        <button
                          type="button"
                          className="btn btn-sm btn-primary"
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
        <div className="h2-admin__research-progress" role="status">
          <span>{t('admin.research.progress', { done: decided, count: pending.length })}</span>
          <progress
            max={pending.length}
            value={decided}
            aria-label={t('admin.research.progressLabel')}
          />
        </div>
      )}
    </div>
  );
}
