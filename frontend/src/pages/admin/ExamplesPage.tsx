import './adminText';
import { useCallback, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { api, ApiError } from '@/api/client';
import type { AdminHomeExample, AudiencePath, ResultCard } from '@/api/types';
import { Icon } from '@/components/Icon';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { AdminEmpty, AdminHead, AdminLoading } from './AdminHead';
import { CHECK_OUTCOMES, CHECK_TONE, oneOf, PATHS, SHOWN_PER_PATH } from './adminModel';

const EXPECTED_MAX = 3;
const COMMAND = 'helper2 examples';

interface Expected {
  id: string;
  title: string;
}
interface Draft {
  label: string;
  query: string;
  path: AudiencePath;
  expected: Expected[];
}
const EMPTY: Draft = { label: '', query: '', path: 'USE_AI', expected: [] };

/** What went wrong with the last action, in words for the admin. */
type Notice = { kind: 'duplicate' | 'conflict' | 'failed' } | { kind: 'invalid'; detail: string };

function noticeOf(e: unknown): Notice {
  if (!(e instanceof ApiError)) return { kind: 'failed' };
  const code = e.problem?.code;
  if (code === 'duplicate') return { kind: 'duplicate' };
  if (code === 'version-conflict') return { kind: 'conflict' };
  if (code === 'invalid-request' && e.problem?.detail)
    return { kind: 'invalid', detail: e.problem.detail };
  return { kind: 'failed' };
}

/**
 * The back office's Examples part (ADR 0049, UJ-9): the examples the home page picks from, each with its last
 * quality check in words. The check filter lives in the address, so the overview can link to the hidden ones. One
 * action at a time; a change made on an old version reloads the list.
 */
export default function ExamplesPage() {
  const { t, i18n } = useTranslation();
  const [params, setParams] = useSearchParams();
  const outcome = oneOf(params.get('outcome'), CHECK_OUTCOMES);
  const examples = useApi(
    `examples/${outcome ?? ''}`,
    (s) => adminApi.listAdminHomeExamples(outcome, s),
    { cache: false },
  );
  const all = useApi('examples/', (s) => adminApi.listAdminHomeExamples(undefined, s), {
    cache: false,
  });
  const { retry, focusRef: heading } = useRetry(examples.reload, examples.loading);
  const [editing, setEditing] = useState<AdminHomeExample | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [drafted, setDrafted] = useState<number | null>(null);
  const [draftError, setDraftError] = useState<string | null>(null);
  // Stable, so the confirm's Cancel takes focus once when it appears, not on every render.
  const focusOnMount = useCallback((el: HTMLButtonElement | null) => el?.focus(), []);

  const reload = () => {
    examples.reload();
    all.reload();
  };
  /**
   * One action at a time; a 409 version-conflict reloads, so the next try starts from what is stored.
   * Resolves to null when done, else to what went wrong (also shown as the notice).
   */
  const act = async (action: () => Promise<unknown>): Promise<Notice | null> => {
    if (busy) return { kind: 'failed' };
    setBusy(true);
    setNotice(null);
    try {
      await action();
      reload();
      return null;
    } catch (e) {
      const n = noticeOf(e);
      setNotice(n);
      if (n.kind === 'conflict') reload();
      return n;
    } finally {
      setBusy(false);
    }
  };

  const save = (draft: Draft) =>
    act(() => {
      const body = {
        label: draft.label,
        query: draft.query,
        path: draft.path,
        expectedResourceIds: draft.expected.map((x) => x.id),
      };
      return editing
        ? adminApi.changeHomeExample(editing.id, {
            ...body,
            status: editing.status,
            version: editing.version,
          })
        : adminApi.addHomeExample(body);
    }).then((failed) => {
      // After a conflict the editor would keep sending the old version: close it, so it opens again
      // on what is stored now.
      if (!failed || failed.kind === 'conflict') setEditing(null);
      return !failed;
    });
  const setStatus = (e: AdminHomeExample, status: AdminHomeExample['status']) =>
    void act(() =>
      adminApi.changeHomeExample(e.id, {
        label: e.label,
        query: e.query,
        path: e.path,
        expectedResourceIds: e.expected.map((x) => x.id),
        status,
        version: e.version,
      }),
    );
  const remove = (id: string) =>
    void act(() => adminApi.removeHomeExample(id)).then(() => setRemoving(null));

  /** One model call: the drafts wait in the review queue, nothing changes here until one is accepted. */
  const draft = async () => {
    if (busy) return;
    setBusy(true);
    setDrafted(null);
    setDraftError(null);
    try {
      setDrafted((await adminApi.draftHomeExamples()).drafted);
    } catch (e) {
      setDraftError(
        e instanceof ApiError && e.problem?.detail ? e.problem.detail : t('admin.examples.failed'),
      );
    } finally {
      setBusy(false);
    }
  };
  const shown = (path: AudiencePath) =>
    (all.data ?? []).filter(
      (e) => e.path === path && e.status === 'ACTIVE' && e.check.outcome === 'PASS',
    ).length;
  const setOutcome = (value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set('outcome', value);
    else next.delete('outcome');
    setParams(next);
  };
  const disabled = busy || undefined;
  const list = examples.data ?? [];

  return (
    <section aria-labelledby="ex-h" className="h2-admin__part">
      <AdminHead
        id="ex-h"
        title={t('admin.examples.title')}
        command={COMMAND}
        total={examples.loading ? null : (examples.data?.length ?? null)}
        headingRef={heading}
      />
      {all.data && (
        <p className="muted">
          {t('admin.examples.lede', { use: shown('USE_AI'), build: shown('BUILD_WITH_AI') })}
        </p>
      )}
      {all.data &&
        PATHS.filter((p) => shown(p) < SHOWN_PER_PATH).map((p) => (
          <p key={p} className="banner banner-warn">
            <Icon name="warn" /> {t('admin.examples.few', { path: t(`admin.examples.path.${p}`) })}
          </p>
        ))}
      {notice && (
        <p role="alert" className="banner banner-warn">
          <Icon name="warn" />{' '}
          {notice.kind === 'invalid'
            ? t('admin.examples.invalid', { detail: notice.detail })
            : t(`admin.examples.${notice.kind}`)}
        </p>
      )}

      <div className="h2-admin__tools">
        <button type="button" className="btn" aria-disabled={disabled} onClick={() => void draft()}>
          {t('admin.examples.draft.button')}
        </button>
        <span className="muted small">{t('admin.examples.draft.hint')}</span>
      </div>
      {drafted !== null && (
        <p role="status">
          {drafted === 0
            ? t('admin.examples.draft.none')
            : t('admin.examples.draft.done', { count: drafted })}{' '}
          {drafted > 0 && (
            <Link to="/admin/review?kind=EXAMPLE_CANDIDATE">{t('admin.examples.draft.open')}</Link>
          )}
        </p>
      )}
      {draftError && (
        <p role="alert" className="banner banner-warn">
          <Icon name="warn" /> {t('admin.examples.draft.unavailable', { detail: draftError })}
        </p>
      )}

      <ExampleForm
        key={editing?.id ?? 'new'}
        editing={editing}
        busy={busy}
        onSave={save}
        onCancel={() => setEditing(null)}
      />

      <div className="h2-admin__tools">
        <label>
          {t('admin.examples.checkFilter')}
          <select value={outcome ?? ''} onChange={(e) => setOutcome(e.target.value)}>
            <option value="">{t('admin.examples.allChecks')}</option>
            {CHECK_OUTCOMES.map((o) => (
              <option key={o} value={o}>
                {t(`admin.examples.checkName.${o}`)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {examples.loading ? (
        <AdminLoading command={COMMAND} />
      ) : examples.error !== undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.examples.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : list.length === 0 ? (
        <AdminEmpty command={COMMAND} text={t('admin.examples.empty')} />
      ) : (
        <ul className="h2-admin__list" aria-labelledby="ex-h">
          {list.map((e) => (
            <li key={e.id} className="v4-row h2-admin__row">
              <p className="h2-admin__meta">
                <span>{t(`admin.examples.path.${e.path}`)}</span>
                <span>{t(`admin.examples.origin.${e.origin}`)}</span>
                {e.check.checkedAt && (
                  <span>
                    {t('admin.examples.checkedAt', {
                      at: formatDate(e.check.checkedAt, i18n.language, 'INSTANT'),
                    })}
                  </span>
                )}
              </p>
              <p className="h2-admin__reason">{e.label}</p>
              <p className="h2-admin__details">{e.query}</p>
              <p>
                <StatusPill tone={e.status === 'PAUSED' ? 'later' : CHECK_TONE[e.check.outcome]}>
                  {e.status === 'PAUSED'
                    ? t('admin.examples.status.PAUSED')
                    : t(`admin.examples.check.${e.check.outcome}`, { rank: e.check.bestRank })}
                </StatusPill>
              </p>
              <p className="h2-admin__details">
                {t('admin.examples.expected')}:{' '}
                {e.expected.map((x, i) => (
                  <span key={x.id}>
                    {i > 0 && ' · '}
                    {x.title ? (
                      <a href={resourcePath(x.id, x.title)}>{x.title}</a>
                    ) : (
                      <span>
                        {x.id} ({t('admin.examples.missing')})
                      </span>
                    )}
                  </span>
                ))}
              </p>
              {removing === e.id ? (
                <div className="h2-admin__actions" role="group">
                  <span>{t('admin.examples.confirmRemove', { label: e.label })}</span>
                  <button
                    type="button"
                    className="btn"
                    ref={focusOnMount}
                    onClick={() => setRemoving(null)}
                  >
                    {t('admin.examples.cancel')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    aria-disabled={disabled}
                    onClick={() => remove(e.id)}
                  >
                    {t('admin.examples.confirm')}
                  </button>
                </div>
              ) : (
                <div className="h2-admin__actions">
                  <button
                    type="button"
                    className="btn"
                    aria-disabled={disabled}
                    onClick={() => setEditing(e)}
                  >
                    {t('admin.examples.edit')} <span className="sr-only">{e.label}</span>
                  </button>
                  <button
                    type="button"
                    className="btn"
                    aria-disabled={disabled}
                    onClick={() => setStatus(e, e.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE')}
                  >
                    {t(e.status === 'ACTIVE' ? 'admin.examples.pause' : 'admin.examples.activate')}{' '}
                    <span className="sr-only">{e.label}</span>
                  </button>
                  <button
                    type="button"
                    className="btn"
                    aria-disabled={disabled}
                    onClick={() => setRemoving(e.id)}
                  >
                    {t('admin.examples.remove')} <span className="sr-only">{e.label}</span>
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Add an example, or change the one in [editing]. Expected results are picked from a catalog search. */
function ExampleForm({
  editing,
  busy,
  onSave,
  onCancel,
}: {
  editing: AdminHomeExample | null;
  busy: boolean;
  onSave: (draft: Draft) => Promise<boolean>;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<Draft>(
    editing
      ? {
          label: editing.label,
          query: editing.query,
          path: editing.path,
          expected: editing.expected.map((x) => ({ id: x.id, title: x.title ?? x.id })),
        }
      : EMPTY,
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (busy || draft.expected.length === 0) return;
    void onSave(draft).then((ok) => {
      if (ok && !editing) setDraft(EMPTY);
    });
  };
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));

  return (
    <form className="h2-admin__form" onSubmit={submit} aria-labelledby="ex-form-h">
      <h3 id="ex-form-h" className="h2-admin__h">
        {editing
          ? t('admin.examples.form.change', { label: editing.label })
          : t('admin.examples.form.add')}
      </h3>
      <label>
        {t('admin.examples.form.label')}
        <input
          required
          minLength={2}
          maxLength={32}
          value={draft.label}
          aria-describedby="ex-label-hint"
          onChange={(e) => set({ label: e.target.value })}
        />
        <span id="ex-label-hint" className="muted small">
          {t('admin.examples.form.labelHint')}
        </span>
      </label>
      <label>
        {t('admin.examples.form.query')}
        <input
          required
          minLength={3}
          maxLength={200}
          value={draft.query}
          aria-describedby="ex-query-hint"
          onChange={(e) => set({ query: e.target.value })}
        />
        <span id="ex-query-hint" className="muted small">
          {t('admin.examples.form.queryHint')}
        </span>
      </label>
      <label>
        {t('admin.examples.form.path')}
        <select value={draft.path} onChange={(e) => set({ path: e.target.value as AudiencePath })}>
          {PATHS.map((p) => (
            <option key={p} value={p}>
              {t(`admin.examples.path.${p}`)}
            </option>
          ))}
        </select>
      </label>
      <ExpectedPicker expected={draft.expected} onChange={(expected) => set({ expected })} />
      <p className="muted small">{t('admin.examples.form.pendingNote')}</p>
      <div className="h2-admin__actions">
        <button
          type="submit"
          className="btn btn-primary"
          aria-disabled={busy || draft.expected.length === 0 || undefined}
        >
          {t(editing ? 'admin.examples.form.saveChange' : 'admin.examples.form.save')}
        </button>
        {editing && (
          <button type="button" className="btn" onClick={onCancel}>
            {t('admin.examples.form.cancel')}
          </button>
        )}
      </div>
    </form>
  );
}

/** 1 to 3 expected results, found with a plain catalog search (the same search visitors get). */
function ExpectedPicker({
  expected,
  onChange,
}: {
  expected: Expected[];
  onChange: (expected: Expected[]) => void;
}) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [found, setFound] = useState<ResultCard[] | null>(null);
  const find = async () => {
    const words = q.trim();
    if (!words) return;
    try {
      const res = await api.search(new URLSearchParams({ q: words, size: '5' }));
      setFound(res.results);
    } catch {
      setFound([]);
    }
  };
  const full = expected.length >= EXPECTED_MAX;

  return (
    <fieldset className="h2-admin__expected">
      <legend>{t('admin.examples.form.expected')}</legend>
      <p className="muted small">{t('admin.examples.form.expectedHint')}</p>
      {expected.length > 0 && (
        <ul className="plain-list inline-list">
          {expected.map((x) => (
            <li key={x.id}>
              <button
                type="button"
                className="chip"
                aria-label={t('admin.examples.form.drop', { title: x.title })}
                onClick={() => onChange(expected.filter((y) => y.id !== x.id))}
              >
                {x.title} ×
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="h2-admin__tools">
        <label>
          {t('admin.examples.form.find')}
          <input
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void find();
              }
            }}
          />
        </label>
        <button type="button" className="btn" onClick={() => void find()}>
          {t('admin.examples.form.findButton')}
        </button>
      </div>
      {found && found.length === 0 && <p className="muted">{t('admin.examples.form.none')}</p>}
      {found && found.length > 0 && (
        <ul className="plain-list">
          {found.map((r) => (
            <li key={r.id} className="h2-admin__found">
              <span>{r.title}</span>
              <button
                type="button"
                className="btn"
                aria-disabled={full || expected.some((x) => x.id === r.id) || undefined}
                onClick={() => {
                  if (full || expected.some((x) => x.id === r.id)) return;
                  onChange([...expected, { id: r.id, title: r.title }]);
                }}
              >
                {t('admin.examples.form.use')} <span className="sr-only">{r.title}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </fieldset>
  );
}
