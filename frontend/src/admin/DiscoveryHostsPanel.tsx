import './adminText';
import { useCallback, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import type { DiscoveryHostEntry, DiscoveryHostKind } from '@/api/types';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';
import { TableSkeleton } from '@/ui/Skeleton';
import { trimOrNull } from './adminModel';
import { useAdminAction } from './useAdminAction';
import { useFocusReturn } from './useFocusReturn';

const KINDS: DiscoveryHostKind[] = ['WRITING', 'DIRECTORY'];

type Done =
  | { kind: 'added'; host: string; list: DiscoveryHostKind }
  | { kind: 'removed'; host: string }
  | { kind: 'reading'; host: string; on: boolean };

export function DiscoveryHostsPanel() {
  const { t } = useTranslation();
  const hosts = useApi('admin/discovery-hosts', (s) => adminApi.listDiscoveryHosts(s), {
    cache: false,
  });
  const [host, setHost] = useState('');
  const [kind, setKind] = useState<DiscoveryHostKind>('WRITING');
  const [reason, setReason] = useState('');
  const [empty, setEmpty] = useState(false);
  const [removing, setRemoving] = useState<DiscoveryHostEntry | null>(null);
  const [removeReason, setRemoveReason] = useState('');
  const [switching, setSwitching] = useState<DiscoveryHostEntry | null>(null);
  const [switchReason, setSwitchReason] = useState('');
  const { busy, notice, clearNotice, run } = useAdminAction<Done>(hosts.reload);
  const focusOnMount = useCallback((el: HTMLElement | null) => el?.focus(), []);
  const focus = useFocusReturn(removing !== null || switching !== null || hosts.loading);
  const closeRemove = (entry: DiscoveryHostEntry) => {
    focus.returnTo(entry.host, entry.kind);
    setRemoving(null);
  };

  const closeSwitch = (entry: DiscoveryHostEntry) => {
    focus.returnTo(entry.host, entry.kind);
    setSwitching(null);
  };

  const all = (hosts.data ?? hosts.previous)?.hosts ?? [];

  const add = (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (host.trim() === '') {
      setEmpty(true);
      return;
    }
    setEmpty(false);
    void run(async () => {
      const saved = await adminApi.addDiscoveryHost({
        host: host.trim(),
        kind,
        reason: trimOrNull(reason),
      });
      setHost('');
      setReason('');
      return { kind: 'added', host: saved.host, list: saved.kind };
    });
  };

  const confirmRemove = () => {
    if (!removing) return;
    const gone = removing;
    void run(async () => {
      try {
        await adminApi.removeDiscoveryHost(gone.host, trimOrNull(removeReason));
        return { kind: 'removed', host: gone.host };
      } finally {
        closeRemove(gone);
      }
    });
  };

  const confirmSwitch = () => {
    if (!switching) return;
    const changed = switching;
    void run(async () => {
      try {
        const saved = await adminApi.readDirectoryLists(
          changed.host,
          !changed.readsLists,
          trimOrNull(switchReason),
        );
        return { kind: 'reading', host: saved.host, on: saved.readsLists };
      } finally {
        closeSwitch(changed);
      }
    });
  };

  const done = notice?.kind === 'added' || notice?.kind === 'removed' || notice?.kind === 'reading';

  return (
    <section aria-labelledby="dh-h">
      <h3 id="dh-h" className="h2-admin__h">
        {t('admin.settings.hosts.title')}
      </h3>
      <p className="muted">{t('admin.settings.hosts.lede')}</p>
      {notice && (
        <p role={done ? 'status' : 'alert'} className={done ? 'banner' : 'banner banner-warn'}>
          {notice.kind === 'added'
            ? t('admin.settings.hosts.added', {
                host: notice.host,
                list: t(`admin.settings.hosts.kind.${notice.list}`),
              })
            : notice.kind === 'removed'
              ? t('admin.settings.hosts.removed', { host: notice.host })
              : notice.kind === 'reading'
                ? t(`admin.settings.hosts.reading.${notice.on ? 'on' : 'off'}`, {
                    host: notice.host,
                  })
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
          <button type="submit" className="btn btn-primary" aria-disabled={busy || undefined}>
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
              aria-disabled={busy || undefined}
              onClick={confirmRemove}
            >
              {t('admin.settings.hosts.confirmRemove')}
            </button>
            <button
              type="button"
              className="btn"
              aria-disabled={busy || undefined}
              onClick={() => !busy && closeRemove(removing)}
            >
              {t('admin.settings.hosts.cancel')}
            </button>
          </div>
        </div>
      )}
      {switching && (
        <div className="h2-admin__form" role="group" aria-labelledby="dh-switch-h">
          <h4 id="dh-switch-h" ref={focusOnMount} tabIndex={-1} className="h2-admin__h">
            {t(`admin.settings.hosts.switchTitle.${switching.readsLists ? 'off' : 'on'}`, {
              host: switching.host,
            })}
          </h4>
          <p>{t(`admin.settings.hosts.switchEffect.${switching.readsLists ? 'off' : 'on'}`)}</p>
          <label>
            {t('admin.settings.hosts.reason')}
            <input
              type="text"
              value={switchReason}
              maxLength={500}
              onChange={(e) => setSwitchReason(e.target.value)}
            />
          </label>
          <div className="h2-admin__tools">
            <button
              type="button"
              className="btn btn-primary"
              aria-disabled={busy || undefined}
              onClick={confirmSwitch}
            >
              {t(`admin.settings.hosts.confirmSwitch.${switching.readsLists ? 'off' : 'on'}`)}
            </button>
            <button
              type="button"
              className="btn"
              aria-disabled={busy || undefined}
              onClick={() => !busy && closeSwitch(switching)}
            >
              {t('admin.settings.hosts.cancel')}
            </button>
          </div>
        </div>
      )}
      {hosts.loading && all.length === 0 ? (
        <TableSkeleton rows={3} quiet />
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
              <h4 id={id} ref={focus.fallback(k)} tabIndex={-1} className="h2-admin__h">
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
                              at: formatDate(h.addedAt, 'INSTANT'),
                            })}
                            {h.reason && <span className="muted"> · {h.reason}</span>}
                            {h.kind === 'DIRECTORY' && (
                              <>
                                {' · '}
                                {t(`admin.settings.hosts.lists.${h.readsLists ? 'on' : 'off'}`)}
                              </>
                            )}
                          </td>
                          <td>
                            {h.kind === 'DIRECTORY' && (
                              <button
                                type="button"
                                className="btn"
                                aria-disabled={busy || undefined}
                                aria-label={t(
                                  `admin.settings.hosts.switchLabel.${h.readsLists ? 'off' : 'on'}`,
                                  { host: h.host },
                                )}
                                onClick={() => {
                                  if (busy) return;
                                  setRemoving(null);
                                  setSwitching(h);
                                  setSwitchReason('');
                                  clearNotice();
                                }}
                              >
                                {t(`admin.settings.hosts.switch.${h.readsLists ? 'off' : 'on'}`)}
                              </button>
                            )}{' '}
                            <button
                              ref={focus.row(h.host)}
                              type="button"
                              className="btn"
                              aria-disabled={busy || undefined}
                              aria-label={t('admin.settings.hosts.removeLabel', { host: h.host })}
                              onClick={() => {
                                if (busy) return;
                                setSwitching(null);
                                setRemoving(h);
                                setRemoveReason('');
                                clearNotice();
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
