import './adminText';
import { useCallback, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { KnownTargetEntry, KnownTargetKind } from '@/api/types';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';

const KINDS: KnownTargetKind[] = ['VENDOR', 'SERVICE'];

type Notice =
  | { kind: 'added' | 'changed' | 'removed'; name: string }
  | { kind: 'refused'; detail: string }
  | { kind: 'failed' };

type Draft = { kind: KnownTargetKind; name: string; otherNames: string };

const EMPTY: Draft = { kind: 'VENDOR', name: '', otherNames: '' };

export const namesOf = (text: string) =>
  text
    .split('\n')
    .map((n) => n.trim())
    .filter((n) => n !== '');

const bodyOf = (draft: Draft) => ({
  kind: draft.kind,
  name: draft.name.trim(),
  otherNames: namesOf(draft.otherNames),
});

export function KnownTargetsPanel() {
  const { t } = useTranslation();
  const targets = useApi('admin/known-targets', (s) => adminApi.listKnownTargets(s), {
    cache: false,
  });
  const [adding, setAdding] = useState<Draft>(EMPTY);
  const [empty, setEmpty] = useState(false);
  const [editing, setEditing] = useState<KnownTargetEntry | null>(null);
  const [edit, setEdit] = useState<Draft>(EMPTY);
  const [removing, setRemoving] = useState<KnownTargetEntry | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const focusOnMount = useCallback((el: HTMLElement | null) => el?.focus(), []);

  const all = targets.data?.targets ?? [];
  const refused = (e: unknown): Notice =>
    e instanceof ApiError && e.problem?.detail
      ? { kind: 'refused', detail: e.problem.detail }
      : { kind: 'failed' };

  const run = async (action: () => Promise<Notice>, after: () => void) => {
    if (busy) return;
    setBusy(true);
    try {
      setNotice(await action());
      after();
    } catch (err) {
      setNotice(refused(err));
    } finally {
      setBusy(false);
      targets.reload();
    }
  };

  const add = (e: FormEvent) => {
    e.preventDefault();
    if (adding.name.trim() === '') {
      setEmpty(true);
      return;
    }
    setEmpty(false);
    void run(
      async () => {
        const saved = await adminApi.addKnownTarget(bodyOf(adding));
        return { kind: 'added', name: saved.name };
      },
      () => setAdding(EMPTY),
    );
  };

  const saveEdit = (e: FormEvent) => {
    e.preventDefault();
    if (!editing || edit.name.trim() === '') return;
    void run(
      async () => {
        const saved = await adminApi.changeKnownTarget(editing.id, bodyOf(edit));
        return { kind: 'changed', name: saved.name };
      },
      () => setEditing(null),
    );
  };

  const confirmRemove = () => {
    if (!removing) return;
    const gone = removing;
    void run(
      async () => {
        await adminApi.removeKnownTarget(gone.id);
        return { kind: 'removed', name: gone.name };
      },
      () => setRemoving(null),
    );
  };

  const ok = notice?.kind === 'added' || notice?.kind === 'changed' || notice?.kind === 'removed';

  return (
    <section aria-labelledby="kt-h">
      <h3 id="kt-h" className="h2-admin__h">
        {t('admin.settings.targets.title')}
      </h3>
      <p className="muted">{t('admin.settings.targets.lede')}</p>
      {notice && (
        <p role={ok ? 'status' : 'alert'} className={ok ? 'banner' : 'banner banner-warn'}>
          {notice.kind === 'refused'
            ? t('admin.settings.targets.refused', { detail: notice.detail })
            : notice.kind === 'failed'
              ? t('admin.settings.targets.failed')
              : t(`admin.settings.targets.${notice.kind}`, { name: notice.name })}
        </p>
      )}
      <form className="h2-admin__form" noValidate onSubmit={add} aria-labelledby="kt-add-h">
        <h4 id="kt-add-h" className="h2-admin__h">
          {t('admin.settings.targets.addTitle')}
        </h4>
        <label>
          {t('admin.settings.targets.kindLabel')}
          <select
            value={adding.kind}
            onChange={(e) => setAdding({ ...adding, kind: e.target.value as KnownTargetKind })}
          >
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t(`admin.settings.targets.kind.${k}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('admin.settings.targets.name')}
          <input
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder="Slack"
            maxLength={60}
            value={adding.name}
            aria-invalid={empty}
            aria-describedby={empty ? 'kt-err' : undefined}
            onChange={(e) => setAdding({ ...adding, name: e.target.value })}
          />
        </label>
        {empty && (
          <p id="kt-err" role="alert" className="banner banner-warn">
            {t('admin.settings.targets.empty')}
          </p>
        )}
        <label>
          {t('admin.settings.targets.otherNames')}
          <textarea
            rows={2}
            autoComplete="off"
            spellCheck={false}
            value={adding.otherNames}
            onChange={(e) => setAdding({ ...adding, otherNames: e.target.value })}
          />
        </label>
        <div className="h2-admin__tools">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {t('admin.settings.targets.add')}
          </button>
        </div>
      </form>
      {editing && (
        <form className="h2-admin__form" noValidate onSubmit={saveEdit} aria-labelledby="kt-edit-h">
          <h4 id="kt-edit-h" ref={focusOnMount} tabIndex={-1} className="h2-admin__h">
            {t('admin.settings.targets.editTitle', { name: editing.name })}
          </h4>
          <p>{t('admin.settings.targets.editEffect')}</p>
          <label>
            {t('admin.settings.targets.kindLabel')}
            <select
              value={edit.kind}
              onChange={(e) => setEdit({ ...edit, kind: e.target.value as KnownTargetKind })}
            >
              {KINDS.map((k) => (
                <option key={k} value={k}>
                  {t(`admin.settings.targets.kind.${k}`)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('admin.settings.targets.name')}
            <input
              type="text"
              autoComplete="off"
              spellCheck={false}
              maxLength={60}
              value={edit.name}
              onChange={(e) => setEdit({ ...edit, name: e.target.value })}
            />
          </label>
          <label>
            {t('admin.settings.targets.otherNames')}
            <textarea
              rows={3}
              autoComplete="off"
              spellCheck={false}
              value={edit.otherNames}
              onChange={(e) => setEdit({ ...edit, otherNames: e.target.value })}
            />
          </label>
          <div className="h2-admin__tools">
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {t('admin.settings.targets.save')}
            </button>
            <button type="button" className="btn" disabled={busy} onClick={() => setEditing(null)}>
              {t('admin.settings.targets.cancel')}
            </button>
          </div>
        </form>
      )}
      {removing && (
        <div className="h2-admin__form" role="group" aria-labelledby="kt-remove-h">
          <h4 id="kt-remove-h" ref={focusOnMount} tabIndex={-1} className="h2-admin__h">
            {t('admin.settings.targets.removeTitle', { name: removing.name })}
          </h4>
          <p>
            {removing.relationships === 0
              ? t('admin.settings.targets.removeNone')
              : t('admin.settings.targets.removeEffect', { count: removing.relationships })}
          </p>
          <div className="h2-admin__tools">
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy}
              onClick={confirmRemove}
            >
              {t('admin.settings.targets.confirmRemove')}
            </button>
            <button type="button" className="btn" disabled={busy} onClick={() => setRemoving(null)}>
              {t('admin.settings.targets.cancel')}
            </button>
          </div>
        </div>
      )}
      {targets.loading && all.length === 0 ? (
        <p role="status" className="muted">
          {t('common.loading')}
        </p>
      ) : targets.error !== undefined && all.length === 0 ? (
        <StatePanel
          tone="error"
          level={4}
          title={t('admin.settings.targets.loadError')}
          next={<RetryButton onClick={targets.reload} />}
        />
      ) : (
        KINDS.map((k) => {
          const listed = all.filter((x) => x.kind === k);
          const id = `kt-${k.toLowerCase()}`;
          return (
            <section key={k} aria-labelledby={id}>
              <h4 id={id} className="h2-admin__h">
                {t(`admin.settings.targets.listTitle.${k}`, { count: listed.length })}
              </h4>
              {listed.length === 0 ? (
                <p className="muted">{t('admin.settings.targets.none')}</p>
              ) : (
                <div className="table-wrap">
                  <table aria-labelledby={id}>
                    <thead>
                      <tr>
                        <th scope="col">{t('admin.settings.targets.col.name')}</th>
                        <th scope="col">{t('admin.settings.targets.col.otherNames')}</th>
                        <th scope="col">{t('admin.settings.targets.col.relationships')}</th>
                        <th scope="col">{t('admin.settings.targets.col.added')}</th>
                        <th scope="col">{t('admin.settings.targets.col.actions')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {listed.map((x) => (
                        <tr key={x.id}>
                          <th scope="row">{x.name}</th>
                          <td>{x.otherNames.join(', ')}</td>
                          <td>{x.relationships}</td>
                          <td>
                            {t('admin.settings.targets.addedBy', {
                              who: x.addedBy,
                              at: formatDate(x.addedAt, 'INSTANT'),
                            })}
                            {x.origin === 'DISCOVERED' &&
                              ` · ${t('admin.settings.targets.discovered')}`}
                          </td>
                          <td>
                            <div className="h2-admin__tools">
                              <button
                                type="button"
                                className="btn"
                                disabled={busy}
                                aria-label={t('admin.settings.targets.editLabel', { name: x.name })}
                                onClick={() => {
                                  setEditing(x);
                                  setEdit({
                                    kind: x.kind,
                                    name: x.name,
                                    otherNames: x.otherNames.join('\n'),
                                  });
                                  setRemoving(null);
                                  setNotice(null);
                                }}
                              >
                                {t('admin.settings.targets.edit')}
                              </button>
                              <button
                                type="button"
                                className="btn"
                                disabled={busy}
                                aria-label={t('admin.settings.targets.removeLabel', {
                                  name: x.name,
                                })}
                                onClick={() => {
                                  setRemoving(x);
                                  setEditing(null);
                                  setNotice(null);
                                }}
                              >
                                {t('admin.settings.targets.remove')}
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          );
        })
      )}
    </section>
  );
}
