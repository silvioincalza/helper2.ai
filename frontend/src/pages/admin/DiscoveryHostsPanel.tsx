import './adminText';
import { useCallback, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { DiscoveryHostEntry, DiscoveryHostKind } from '@/api/types';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';

const KINDS: DiscoveryHostKind[] = ['WRITING', 'DIRECTORY'];

/** What happened to the last change, in words. */
type Notice =
  | { kind: 'added'; host: string; list: DiscoveryHostKind }
  | { kind: 'removed'; host: string }
  | { kind: 'refused'; detail: string }
  | { kind: 'failed' };

/**
 * The hosts discovery knows by name (ADR 0063, UJ-9). A post on a writing site is proposed as an
 * article; a page on a directory is never kept. The admin adds a host with a reason, or removes one
 * after a review; the next discovery run uses the lists as they are then.
 */
export function DiscoveryHostsPanel() {
  const { t, i18n } = useTranslation();
  const hosts = useApi('admin/discovery-hosts', (s) => adminApi.listDiscoveryHosts(s), {
    cache: false,
  });
  const [host, setHost] = useState('');
  const [kind, setKind] = useState<DiscoveryHostKind>('WRITING');
  const [reason, setReason] = useState('');
  const [empty, setEmpty] = useState(false);
  const [removing, setRemoving] = useState<DiscoveryHostEntry | null>(null);
  const [removeReason, setRemoveReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const focusOnMount = useCallback((el: HTMLElement | null) => el?.focus(), []);

  const all = hosts.data?.hosts ?? [];
  const refused = (e: unknown): Notice =>
    e instanceof ApiError && e.problem?.detail
      ? { kind: 'refused', detail: e.problem.detail }
      : { kind: 'failed' };

  const add = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (host.trim() === '') {
      setEmpty(true);
      return;
    }
    setEmpty(false);
    setBusy(true);
    try {
      const saved = await adminApi.addDiscoveryHost({
        host: host.trim(),
        kind,
        reason: reason.trim() === '' ? null : reason.trim(),
      });
      setNotice({ kind: 'added', host: saved.host, list: saved.kind });
      setHost('');
      setReason('');
    } catch (err) {
      setNotice(refused(err));
    } finally {
      setBusy(false);
      hosts.reload();
    }
  };

  const confirmRemove = async () => {
    if (!removing || busy) return;
    setBusy(true);
    try {
      await adminApi.removeDiscoveryHost(
        removing.host,
        removeReason.trim() === '' ? null : removeReason.trim(),
      );
      setNotice({ kind: 'removed', host: removing.host });
    } catch (err) {
      setNotice(refused(err));
    } finally {
      setRemoving(null);
      setBusy(false);
      hosts.reload();
    }
  };

  return (
    <section aria-labelledby="dh-h">
      <h3 id="dh-h" className="h2-admin__h">
        {t('admin.settings.hosts.title')}
      </h3>
      <p className="muted">{t('admin.settings.hosts.lede')}</p>
      {notice && (
        <p
          role={notice.kind === 'added' || notice.kind === 'removed' ? 'status' : 'alert'}
          className={
            notice.kind === 'added' || notice.kind === 'removed' ? 'banner' : 'banner banner-warn'
          }
        >
          {notice.kind === 'added'
            ? t('admin.settings.hosts.added', {
                host: notice.host,
                list: t(`admin.settings.hosts.kind.${notice.list}`),
              })
            : notice.kind === 'removed'
              ? t('admin.settings.hosts.removed', { host: notice.host })
              : notice.kind === 'refused'
                ? t('admin.settings.hosts.refused', { detail: notice.detail })
                : t('admin.settings.hosts.failed')}
        </p>
      )}
      <form className="h2-admin__form" noValidate onSubmit={add} aria-labelledby="dh-add-h">
        <h4 id="dh-add-h" className="h2-admin__h">
          {t('admin.settings.hosts.addTitle')}
        </h4>
        <label>
          {t('admin.settings.hosts.host')}
          <input
            type="text"
            inputMode="url"
            autoComplete="off"
            spellCheck={false}
            placeholder="techcrunch.com"
            maxLength={260}
            value={host}
            aria-invalid={empty}
            aria-describedby={empty ? 'dh-err' : undefined}
            onChange={(e) => setHost(e.target.value)}
          />
        </label>
        {empty && (
          <p id="dh-err" role="alert" className="banner banner-warn">
            {t('admin.settings.hosts.empty')}
          </p>
        )}
        <label>
          {t('admin.settings.hosts.list')}
          <select value={kind} onChange={(e) => setKind(e.target.value as DiscoveryHostKind)}>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t(`admin.settings.hosts.kind.${k}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('admin.settings.hosts.reason')}
          <input
            type="text"
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="h2-admin__tools">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {t('admin.settings.hosts.add')}
          </button>
        </div>
      </form>
      {removing && (
        <div className="h2-admin__form" role="group" aria-labelledby="dh-review-h">
          <h4 id="dh-review-h" ref={focusOnMount} tabIndex={-1} className="h2-admin__h">
            {t('admin.settings.hosts.removeTitle', {
              host: removing.host,
              list: t(`admin.settings.hosts.kind.${removing.kind}`),
            })}
          </h4>
          <p>{t(`admin.settings.hosts.removeEffect.${removing.kind}`)}</p>
          <label>
            {t('admin.settings.hosts.reason')}
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
              {t('admin.settings.hosts.confirmRemove')}
            </button>
            <button type="button" className="btn" disabled={busy} onClick={() => setRemoving(null)}>
              {t('admin.settings.hosts.cancel')}
            </button>
          </div>
        </div>
      )}
      {hosts.loading && all.length === 0 ? (
        <p role="status" className="muted">
          {t('common.loading')}
        </p>
      ) : hosts.error !== undefined && all.length === 0 ? (
        <StatePanel
          tone="error"
          level={4}
          title={t('admin.settings.hosts.loadError')}
          next={<RetryButton onClick={hosts.reload} />}
        />
      ) : (
        KINDS.map((k) => {
          const listed = all.filter((h) => h.kind === k);
          const id = `dh-${k.toLowerCase()}`;
          return (
            <section key={k} aria-labelledby={id}>
              <h4 id={id} className="h2-admin__h">
                {t(`admin.settings.hosts.listTitle.${k}`, { count: listed.length })}
              </h4>
              {listed.length === 0 ? (
                <p className="muted">{t('admin.settings.hosts.none')}</p>
              ) : (
                <div className="table-wrap">
                  <table aria-labelledby={id}>
                    <thead>
                      <tr>
                        <th scope="col">{t('admin.settings.hosts.col.host')}</th>
                        <th scope="col">{t('admin.settings.hosts.col.added')}</th>
                        <th scope="col">{t('admin.settings.hosts.col.actions')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {listed.map((h) => (
                        <tr key={h.host}>
                          <th scope="row">
                            <code>{h.host}</code>
                          </th>
                          <td>
                            {t('admin.settings.hosts.addedBy', {
                              who: h.addedBy,
                              at: formatDate(h.addedAt, i18n.language, 'INSTANT'),
                            })}
                            {h.reason && <span className="muted"> · {h.reason}</span>}
                          </td>
                          <td>
                            <button
                              type="button"
                              className="btn"
                              disabled={busy}
                              aria-label={t('admin.settings.hosts.removeLabel', { host: h.host })}
                              onClick={() => {
                                setRemoving(h);
                                setRemoveReason('');
                                setNotice(null);
                              }}
                            >
                              {t('admin.settings.hosts.remove')}
                            </button>
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
