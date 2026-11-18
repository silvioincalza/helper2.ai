import './adminText';
import { useCallback, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { ShadowLibraryEntry } from '@/api/types';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';

type Notice =
  | { kind: 'added'; name: string }
  | { kind: 'removed'; name: string }
  | { kind: 'refused'; detail: string }
  | { kind: 'failed' };

export function ShadowLibrariesPanel() {
  const { t } = useTranslation();
  const libraries = useApi('admin/shadow-libraries', (s) => adminApi.listShadowLibraries(s), {
    cache: false,
  });
  const [name, setName] = useState('');
  const [reason, setReason] = useState('');
  const [empty, setEmpty] = useState(false);
  const [removing, setRemoving] = useState<ShadowLibraryEntry | null>(null);
  const [removeReason, setRemoveReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const focusOnMount = useCallback((el: HTMLElement | null) => el?.focus(), []);

  const all = libraries.data?.libraries ?? [];
  const max = libraries.data?.max ?? 0;
  const refused = (e: unknown): Notice =>
    e instanceof ApiError && e.problem?.detail
      ? { kind: 'refused', detail: e.problem.detail }
      : { kind: 'failed' };

  const add = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (name.trim() === '') {
      setEmpty(true);
      return;
    }
    setEmpty(false);
    setBusy(true);
    try {
      const saved = await adminApi.addShadowLibrary({
        name: name.trim(),
        reason: reason.trim() === '' ? null : reason.trim(),
      });
      setNotice({ kind: 'added', name: saved.name });
      setName('');
      setReason('');
    } catch (err) {
      setNotice(refused(err));
    } finally {
      setBusy(false);
      libraries.reload();
    }
  };

  const confirmRemove = async () => {
    if (!removing || busy) return;
    setBusy(true);
    try {
      await adminApi.removeShadowLibrary(
        removing.name,
        removeReason.trim() === '' ? null : removeReason.trim(),
      );
      setNotice({ kind: 'removed', name: removing.name });
    } catch (err) {
      setNotice(refused(err));
    } finally {
      setRemoving(null);
      setBusy(false);
      libraries.reload();
    }
  };

  const done = notice?.kind === 'added' || notice?.kind === 'removed';

  return (
    <section aria-labelledby="sl-h">
      <h3 id="sl-h" className="h2-admin__h">
        {t('admin.settings.shadow.title')}
      </h3>
      <p className="muted">{t('admin.settings.shadow.lede')}</p>
      {notice && (
        <p role={done ? 'status' : 'alert'} className={done ? 'banner' : 'banner banner-warn'}>
          {notice.kind === 'added'
            ? t('admin.settings.shadow.added', { name: notice.name })
            : notice.kind === 'removed'
              ? t('admin.settings.shadow.removed', { name: notice.name })
              : notice.kind === 'refused'
                ? t('admin.settings.shadow.refused', { detail: notice.detail })
                : t('admin.settings.shadow.failed')}
        </p>
      )}
      <form className="h2-admin__form" noValidate onSubmit={add} aria-labelledby="sl-add-h">
        <h4 id="sl-add-h" className="h2-admin__h">
          {t('admin.settings.shadow.addTitle')}
        </h4>
        <label>
          {t('admin.settings.shadow.name')}
          <input
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder="libgen"
            maxLength={70}
            value={name}
            aria-invalid={empty}
            aria-describedby={empty ? 'sl-err' : undefined}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        {empty && (
          <p id="sl-err" role="alert" className="banner banner-warn">
            {t('admin.settings.shadow.empty')}
          </p>
        )}
        <label>
          {t('admin.settings.shadow.reason')}
          <input
            type="text"
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="h2-admin__tools">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {t('admin.settings.shadow.add')}
          </button>
        </div>
      </form>
      {removing && (
        <div className="h2-admin__form" role="group" aria-labelledby="sl-review-h">
          <h4 id="sl-review-h" ref={focusOnMount} tabIndex={-1} className="h2-admin__h">
            {t('admin.settings.shadow.removeTitle', { name: removing.name })}
          </h4>
          <p>{t('admin.settings.shadow.removeEffect')}</p>
          <label>
            {t('admin.settings.shadow.reason')}
            <input
              type="text"
              value={removeReason}
              maxLength={500}
              onChange={(e) => setRemoveReason(e.target.value)}
            />
          </label>
          <div className="h2-admin__tools">
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy}
              onClick={confirmRemove}
            >
              {t('admin.settings.shadow.confirmRemove')}
            </button>
            <button type="button" className="btn" disabled={busy} onClick={() => setRemoving(null)}>
              {t('admin.settings.shadow.cancel')}
            </button>
          </div>
        </div>
      )}
      {libraries.loading && all.length === 0 ? (
        <p role="status" className="muted">
          {t('common.loading')}
        </p>
      ) : libraries.error !== undefined && all.length === 0 ? (
        <StatePanel
          tone="error"
          level={4}
          title={t('admin.settings.shadow.loadError')}
          next={<RetryButton onClick={libraries.reload} />}
        />
      ) : (
        <section aria-labelledby="sl-list-h">
          <h4 id="sl-list-h" className="h2-admin__h">
            {t('admin.settings.shadow.listTitle', { count: all.length, max })}
          </h4>
          {all.length === 0 ? (
            <p className="muted">{t('admin.settings.shadow.none')}</p>
          ) : (
            <div className="table-wrap">
              <table aria-labelledby="sl-list-h">
                <thead>
                  <tr>
                    <th scope="col">{t('admin.settings.shadow.col.name')}</th>
                    <th scope="col">{t('admin.settings.shadow.col.added')}</th>
                    <th scope="col">{t('admin.settings.shadow.col.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {all.map((l) => (
                    <tr key={l.name}>
                      <th scope="row">
                        <code>{l.name}</code>
                      </th>
                      <td>
                        {t('admin.settings.shadow.addedBy', {
                          who: l.addedBy,
                          at: formatDate(l.addedAt, 'INSTANT'),
                        })}
                        {l.reason && <span className="muted"> · {l.reason}</span>}
                      </td>
                      <td>
                        <button
                          type="button"
                          className="btn"
                          disabled={busy}
                          aria-label={t('admin.settings.shadow.removeLabel', { name: l.name })}
                          onClick={() => {
                            setRemoving(l);
                            setRemoveReason('');
                            setNotice(null);
                          }}
                        >
                          {t('admin.settings.shadow.remove')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </section>
  );
}
