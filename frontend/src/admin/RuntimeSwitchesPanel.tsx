import './adminText';
import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError, problemDetail } from '@/api/client';
import type { RuntimeSwitchState } from '@/api/types';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { StatusPill } from '@/ui/StatusPill';
import { formatDate } from '@/lib/format';
import { TableSkeleton } from '@/ui/Skeleton';
import { trimOrNull } from './adminModel';

interface Pending {
  setting: RuntimeSwitchState;
  on: boolean | null;
}

type Notice =
  | { kind: 'saved'; label: string; value: string }
  | { kind: 'conflict' }
  | { kind: 'refused'; detail: string }
  | { kind: 'failed' };

const shown = (on: boolean) => String(on);

export function RuntimeSwitchesPanel({
  switches,
  loading,
  error,
  reload,
}: {
  switches: RuntimeSwitchState[];
  loading: boolean;
  error: unknown;
  reload: () => void;
}) {
  const { t } = useTranslation();
  const [pending, setPending] = useState<Pending | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const focusOnMount = useCallback((el: HTMLElement | null) => el?.focus(), []);

  const review = (setting: RuntimeSwitchState, on: boolean | null) => {
    setPending({ setting, on });
    setReason('');
    setNotice(null);
  };

  const confirm = async () => {
    if (!pending || busy) return;
    setBusy(true);
    const { setting, on } = pending;
    const why = trimOrNull(reason);
    try {
      const saved =
        on === null
          ? await adminApi.resetRuntimeSwitch(setting.key, {
              version: setting.version,
              reason: why,
            })
          : await adminApi.changeRuntimeSwitch(setting.key, {
              on,
              version: setting.version,
              reason: why,
            });
      setNotice({ kind: 'saved', label: setting.label, value: shown(saved.on) });
    } catch (e) {
      const code = e instanceof ApiError ? e.problem?.code : undefined;
      const detail = problemDetail(e, { code: ['invalid-request'] });
      if (code === 'version-conflict') setNotice({ kind: 'conflict' });
      else if (detail) setNotice({ kind: 'refused', detail });
      else setNotice({ kind: 'failed' });
    } finally {
      setPending(null);
      setBusy(false);
      reload();
    }
  };

  if (!loading && error === undefined && switches.length === 0) return null;

  return (
    <section aria-labelledby="sw-h">
      <h3 id="sw-h" className="h2-admin__h">
        {t('admin.settings.switches.title')}
      </h3>
      <p className="muted">{t('admin.settings.switches.lede')}</p>
      {notice && (
        <p
          role={notice.kind === 'saved' ? 'status' : 'alert'}
          className={notice.kind === 'saved' ? 'banner' : 'banner banner-warn'}
        >
          {notice.kind === 'saved'
            ? t('admin.settings.switches.saved', { label: notice.label, value: notice.value })
            : notice.kind === 'refused'
              ? t('admin.settings.switches.refused', { detail: notice.detail })
              : t(`admin.settings.switches.${notice.kind}`)}
        </p>
      )}
      {pending && (
        <div className="h2-admin__form" role="group" aria-labelledby="sw-review-h">
          <h4 id="sw-review-h" ref={focusOnMount} tabIndex={-1} className="h2-admin__h">
            {t('admin.settings.switches.reviewTitle')}
          </h4>
          <p>
            {t('admin.settings.switches.diff', {
              label: pending.setting.label,
              before: shown(pending.setting.on),
              after:
                pending.on === null
                  ? `${shown(pending.setting.defaultOn)} (${t('admin.settings.switches.atDefault')})`
                  : shown(pending.on),
            })}
          </p>
          <label>
            {t('admin.settings.switches.reason')}
            <input
              type="text"
              value={reason}
              maxLength={500}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <div className="h2-admin__tools">
            <button type="button" className="btn btn-primary" disabled={busy} onClick={confirm}>
              {t('admin.settings.switches.confirm')}
            </button>
            <button type="button" className="btn" disabled={busy} onClick={() => setPending(null)}>
              {t('admin.settings.switches.cancel')}
            </button>
          </div>
        </div>
      )}
      {loading && switches.length === 0 ? (
        <TableSkeleton rows={1} quiet />
      ) : error !== undefined && switches.length === 0 ? (
        <StatePanel
          tone="error"
          level={4}
          title={t('admin.settings.switches.loadError')}
          next={<RetryButton onClick={reload} />}
        />
      ) : (
        <div className="table-wrap">
          <table aria-labelledby="sw-h">
            <thead>
              <tr>
                <th scope="col">{t('admin.settings.switches.col.setting')}</th>
                <th scope="col">{t('admin.settings.switches.col.now')}</th>
                <th scope="col">{t('admin.settings.switches.col.default')}</th>
                <th scope="col">{t('admin.settings.switches.col.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {switches.map((s) => {
                const action = s.on
                  ? t('admin.settings.switches.turnOff')
                  : t('admin.settings.switches.turnOn');
                return (
                  <tr key={s.key}>
                    <th scope="row">
                      {s.label}
                      <span className="muted"> — {s.help}</span>
                      {!s.inUse && (
                        <span className="muted"> {t('admin.settings.switches.notInUse')}</span>
                      )}
                    </th>
                    <td>
                      <code>{shown(s.on)}</code>{' '}
                      <StatusPill tone={s.overridden ? 'next' : 'ok'}>
                        {s.overridden
                          ? t('admin.settings.switches.changed')
                          : t('admin.settings.switches.atDefault')}
                      </StatusPill>
                    </td>
                    <td>
                      <code>{shown(s.defaultOn)}</code> <code>{s.variable}</code>
                    </td>
                    <td>
                      <div className="h2-admin__tools">
                        <button
                          type="button"
                          className="btn"
                          disabled={busy}
                          aria-label={t('admin.settings.switches.turnLabel', {
                            action,
                            label: s.label,
                          })}
                          onClick={() => review(s, !s.on)}
                        >
                          {action}
                        </button>
                        {s.overridden && (
                          <button
                            type="button"
                            className="btn"
                            disabled={busy}
                            aria-label={`${t('admin.settings.switches.resetToDefault')}: ${s.label}`}
                            onClick={() => review(s, null)}
                          >
                            {t('admin.settings.switches.resetToDefault')}
                          </button>
                        )}
                      </div>
                      <History setting={s} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function History({ setting }: { setting: RuntimeSwitchState }) {
  const { t } = useTranslation();
  if (setting.history.length === 0) return null;
  return (
    <details>
      <summary>{t('admin.settings.switches.history', { count: setting.history.length })}</summary>
      <ul>
        {setting.history.map((h) => (
          <li key={h.version}>
            {t('admin.settings.switches.historyEntry', {
              value: h.on === null ? t('admin.settings.switches.backToDefault') : shown(h.on),
              who: h.changedBy,
              at: formatDate(h.changedAt, 'INSTANT'),
            })}
            {h.reason && <span className="muted"> · {h.reason}</span>}
          </li>
        ))}
      </ul>
    </details>
  );
}
