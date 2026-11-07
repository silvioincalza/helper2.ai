import './adminText';
import { useCallback, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { RuntimeSettingState } from '@/api/types';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill } from '@/components/ui/StatusPill';
import { formatDate } from '@/lib/format';

/** A change the admin is about to confirm: a new value, or back to the default (value null). */
interface Pending {
  setting: RuntimeSettingState;
  value: number | null;
}

/** What happened to the last change, in words. */
type Notice =
  | { kind: 'saved'; label: string; value: string }
  | { kind: 'conflict' }
  | { kind: 'refused'; detail: string }
  | { kind: 'failed' };

/** A value in words: the number with its unit, or "No cap". */
function useValueText() {
  const { t } = useTranslation();
  return (value: number | null | undefined, unit: string) =>
    value === null || value === undefined ? t('admin.settings.noCap') : `${value} ${unit}`;
}

/** A whole number from 0 to [max], or null. */
function parseValue(text: string, max: number): number | null {
  if (!/^\d+$/.test(text.trim())) return null;
  const n = Number(text.trim());
  return n <= max ? n : null;
}

/**
 * The spending limits an administrator may change at runtime (ADR 0007, UJ-9). Each row: the value
 * now, the default, the allowed range and one line of help. A change goes through a review of the
 * old and the new value, with an optional reason; History restores an earlier value the same way.
 */
export function RuntimeSettingsPanel({
  settings,
  loading,
  error,
  reload,
}: {
  settings: RuntimeSettingState[];
  loading: boolean;
  error: unknown;
  reload: () => void;
}) {
  const { t } = useTranslation();
  const valueText = useValueText();
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [invalid, setInvalid] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  // Stable, so the review's heading takes focus once when it appears.
  const focusOnMount = useCallback((el: HTMLElement | null) => el?.focus(), []);

  const startEdit = (s: RuntimeSettingState) => {
    setEditing(s.key);
    setDraft(String(s.value ?? ''));
    setInvalid(false);
    setPending(null);
  };
  const review = (s: RuntimeSettingState, value: number | null) => {
    setPending({ setting: s, value });
    setReason('');
    setNotice(null);
  };
  const submitEdit = (e: FormEvent, s: RuntimeSettingState) => {
    e.preventDefault();
    const value = parseValue(draft, s.max);
    setInvalid(value === null);
    if (value !== null) review(s, value);
  };
  const cancel = () => {
    setPending(null);
    setEditing(null);
  };

  const confirm = async () => {
    if (!pending || busy) return;
    setBusy(true);
    const { setting, value } = pending;
    const why = reason.trim() === '' ? null : reason.trim();
    try {
      const saved =
        value === null
          ? await adminApi.resetRuntimeSetting(setting.key, {
              version: setting.version,
              reason: why,
            })
          : await adminApi.changeRuntimeSetting(setting.key, {
              value,
              version: setting.version,
              reason: why,
            });
      setNotice({ kind: 'saved', label: setting.label, value: valueText(saved.value, saved.unit) });
      setPending(null);
      setEditing(null);
    } catch (e) {
      const code = e instanceof ApiError ? e.problem?.code : undefined;
      if (code === 'version-conflict') setNotice({ kind: 'conflict' });
      else if (code === 'invalid-request' && e instanceof ApiError && e.problem?.detail)
        setNotice({ kind: 'refused', detail: e.problem.detail });
      else setNotice({ kind: 'failed' });
      setPending(null);
    } finally {
      setBusy(false);
      reload();
    }
  };

  return (
    <section aria-labelledby="rt-h">
      <h3 id="rt-h" className="h2-admin__h">
        {t('admin.settings.runtime.title')}
      </h3>
      <p className="muted">{t('admin.settings.runtime.lede')}</p>
      {notice && (
        <p
          role={notice.kind === 'saved' ? 'status' : 'alert'}
          className={notice.kind === 'saved' ? 'banner' : 'banner banner-warn'}
        >
          {notice.kind === 'saved'
            ? t('admin.settings.runtime.saved', { label: notice.label, value: notice.value })
            : notice.kind === 'refused'
              ? t('admin.settings.runtime.refused', { detail: notice.detail })
              : t(`admin.settings.runtime.${notice.kind}`)}
        </p>
      )}
      {pending && (
        <div className="h2-admin__form" role="group" aria-labelledby="rt-review-h">
          <h4 id="rt-review-h" ref={focusOnMount} tabIndex={-1} className="h2-admin__h">
            {t('admin.settings.runtime.reviewTitle')}
          </h4>
          <p>
            {t('admin.settings.runtime.diff', {
              label: pending.setting.label,
              before: valueText(pending.setting.value, pending.setting.unit),
              after:
                pending.value === null
                  ? `${valueText(pending.setting.defaultValue, pending.setting.unit)} (${t('admin.settings.runtime.atDefault')})`
                  : valueText(pending.value, pending.setting.unit),
            })}
          </p>
          {pending.value === 0 && <p className="muted">{t('admin.settings.runtime.zeroOff')}</p>}
          <label>
            {t('admin.settings.runtime.reason')}
            {/* One line: the server refuses line breaks and other control characters. */}
            <input
              type="text"
              value={reason}
              maxLength={500}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <div className="h2-admin__tools">
            <button type="button" className="btn btn-primary" disabled={busy} onClick={confirm}>
              {t('admin.settings.runtime.confirm')}
            </button>
            <button type="button" className="btn" disabled={busy} onClick={cancel}>
              {t('admin.settings.runtime.cancel')}
            </button>
          </div>
        </div>
      )}
      {loading && settings.length === 0 ? (
        <p role="status" className="muted">
          {t('common.loading')}
        </p>
      ) : error !== undefined && settings.length === 0 ? (
        <StatePanel
          tone="error"
          level={4}
          title={t('admin.settings.runtime.loadError')}
          next={<RetryButton onClick={reload} />}
        />
      ) : (
        <div className="table-wrap">
          <table aria-labelledby="rt-h">
            <thead>
              <tr>
                <th scope="col">{t('admin.settings.runtime.col.setting')}</th>
                <th scope="col">{t('admin.settings.runtime.col.now')}</th>
                <th scope="col">{t('admin.settings.runtime.col.default')}</th>
                <th scope="col">{t('admin.settings.runtime.col.range')}</th>
                <th scope="col">{t('admin.settings.runtime.col.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {settings.map((s) => (
                <tr key={s.key}>
                  <th scope="row">
                    {s.label}
                    <span className="muted"> — {s.help}</span>
                    {!s.inUse && (
                      <span className="muted"> {t('admin.settings.runtime.notInUse')}</span>
                    )}
                  </th>
                  <td>
                    {valueText(s.value, s.unit)}{' '}
                    <StatusPill tone={s.overridden ? 'next' : 'ok'}>
                      {s.overridden
                        ? t('admin.settings.runtime.changed')
                        : t('admin.settings.runtime.atDefault')}
                    </StatusPill>
                  </td>
                  <td>
                    {valueText(s.defaultValue, s.unit)} <code>{s.variable}</code>
                  </td>
                  <td>{t('admin.settings.runtime.range', { max: s.max, unit: s.unit })}</td>
                  <td>
                    {editing === s.key ? (
                      <form
                        className="h2-admin__form"
                        noValidate
                        onSubmit={(e) => submitEdit(e, s)}
                      >
                        <label>
                          {t('admin.settings.runtime.changeLabel', { label: s.label })}
                          <input
                            type="number"
                            inputMode="numeric"
                            min={0}
                            max={s.max}
                            step={1}
                            value={draft}
                            aria-invalid={invalid}
                            aria-describedby={invalid ? `rt-err-${s.key}` : undefined}
                            onChange={(e) => setDraft(e.target.value)}
                          />
                        </label>
                        {invalid && (
                          <p id={`rt-err-${s.key}`} role="alert" className="banner banner-warn">
                            {t('admin.settings.runtime.invalid', { max: s.max })}
                          </p>
                        )}
                        <div className="h2-admin__tools">
                          <button type="submit" className="btn" disabled={busy}>
                            {t('admin.settings.runtime.review')}
                          </button>
                          <button type="button" className="btn" onClick={cancel}>
                            {t('admin.settings.runtime.cancel')}
                          </button>
                        </div>
                      </form>
                    ) : (
                      <div className="h2-admin__tools">
                        <button
                          type="button"
                          className="btn"
                          disabled={busy}
                          aria-label={`${t('admin.settings.runtime.change')}: ${s.label}`}
                          onClick={() => startEdit(s)}
                        >
                          {t('admin.settings.runtime.change')}
                        </button>
                        {s.overridden && (
                          <button
                            type="button"
                            className="btn"
                            disabled={busy}
                            aria-label={`${t('admin.settings.runtime.resetToDefault')}: ${s.label}`}
                            onClick={() => review(s, null)}
                          >
                            {t('admin.settings.runtime.resetToDefault')}
                          </button>
                        )}
                      </div>
                    )}
                    <History setting={s} busy={busy} onRestore={(v) => review(s, v)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** The last changes of one setting, newest first; each earlier one can be restored. */
function History({
  setting,
  busy,
  onRestore,
}: {
  setting: RuntimeSettingState;
  busy: boolean;
  onRestore: (value: number | null) => void;
}) {
  const { t, i18n } = useTranslation();
  const valueText = useValueText();
  if (setting.history.length === 0) return null;
  return (
    <details>
      <summary>{t('admin.settings.runtime.history', { count: setting.history.length })}</summary>
      <ul>
        {setting.history.map((h, i) => {
          const value =
            h.value === null
              ? t('admin.settings.runtime.backToDefault')
              : valueText(h.value, setting.unit);
          const at = formatDate(h.changedAt, i18n.language, 'INSTANT');
          return (
            <li key={h.version}>
              {t('admin.settings.runtime.historyEntry', { value, who: h.changedBy, at })}
              {h.reason && <span className="muted"> · {h.reason}</span>}{' '}
              {i === 0 ? (
                <span className="muted">({t('admin.settings.runtime.current')})</span>
              ) : (
                <button
                  type="button"
                  className="btn"
                  disabled={busy}
                  aria-label={t('admin.settings.runtime.restoreLabel', { value, at })}
                  onClick={() => onRestore(h.value)}
                >
                  {t('admin.settings.runtime.restore')}
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </details>
  );
}
