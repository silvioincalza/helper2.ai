import './adminText';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import type { AiCheckRow } from '@/api/types';
import { ScrollBox } from '@/ui/ScrollBox';
import { resourcePath } from '@/lib/searchState';
import { AdminDialog } from './AdminDialog';

const PAGE = 500;

type Row = AiCheckRow & { skipped?: boolean };

export function AiCheckPanel() {
  const { t } = useTranslation();
  const [rows, setRows] = useState<Row[]>([]);
  const [checked, setChecked] = useState(0);
  const [next, setNext] = useState<string | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const started = next !== undefined;
  const done = next === null;
  const waiting = rows.filter((r) => r.waiting && !r.skipped);

  const checkMore = async () => {
    if (busy || done) return;
    setBusy(true);
    setError(null);
    try {
      const page = await adminApi.checkAiWords(next ?? null, PAGE);
      setRows((r) => [...r, ...page.notAboutAi]);
      setChecked((c) => c + page.checked);
      setNext(page.next ?? null);
    } catch {
      setError(t('admin.aiCheck.failed'));
    } finally {
      setBusy(false);
    }
  };

  const skip = async (targets: Row[]) => {
    setBusy(true);
    setError(null);
    for (const row of targets) {
      try {
        await adminApi.skipEnrichment(row.resourceId, t('admin.aiCheck.reason'));
        setRows((all) =>
          all.map((r) => (r.resourceId === row.resourceId ? { ...r, skipped: true } : r)),
        );
      } catch {
        setError(t('admin.aiCheck.skipFailed', { title: row.title }));
      }
    }
    setBusy(false);
  };

  return (
    <section aria-labelledby="ai-check-h" className="h2-admin__part">
      <h3 id="ai-check-h" className="h2-admin__h">
        {t('admin.aiCheck.title')}
      </h3>
      <p className="muted">{t('admin.aiCheck.lede')}</p>
      <div className="h2-admin__tools">
        <button
          type="button"
          className="btn"
          aria-disabled={busy || done || undefined}
          onClick={() => void checkMore()}
        >
          {busy
            ? t('admin.aiCheck.checking')
            : t(started ? 'admin.aiCheck.more' : 'admin.aiCheck.start')}
        </button>
        {waiting.length > 0 && (
          <button
            type="button"
            className="btn"
            aria-disabled={busy || undefined}
            onClick={() => setConfirm(true)}
          >
            {t('admin.aiCheck.skipAll', { count: waiting.length })}
          </button>
        )}
      </div>
      {started && (
        <p role="status" className="h2-admin__count-line">
          {t('admin.aiCheck.status', { checked, count: rows.length })}
          {done && ` ${t('admin.aiCheck.end')}`}
        </p>
      )}
      {error && (
        <p role="alert" className="banner banner-warn">
          {error}
        </p>
      )}
      {rows.length > 0 && (
        <ScrollBox label={t('admin.aiCheck.title')}>
          <table className="h2-table h2-admin__table" aria-labelledby="ai-check-h">
            <thead>
              <tr>
                <th scope="col">{t('admin.aiCheck.col.title')}</th>
                <th scope="col">{t('admin.aiCheck.col.type')}</th>
                <th scope="col" className="is-num">
                  {t('admin.aiCheck.col.popularity')}
                </th>
                <th scope="col">{t('admin.aiCheck.col.state')}</th>
                <th scope="col">{t('admin.aiCheck.col.action')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.resourceId} className="h2-term-row">
                  <th scope="row">
                    <a href={resourcePath(r.resourceId, r.title)}>{r.title}</a>
                  </th>
                  <td>{t(`type.${r.type}`, { defaultValue: r.type })}</td>
                  <td className="is-num">{r.popularity.toFixed(3)}</td>
                  <td>
                    {r.skipped
                      ? t('admin.aiCheck.skipped')
                      : t(r.waiting ? 'admin.aiCheck.waiting' : 'admin.aiCheck.notWaiting')}
                  </td>
                  <td>
                    {r.waiting && !r.skipped && (
                      <button
                        type="button"
                        className="btn"
                        aria-label={t('admin.aiCheck.skipLabel', { title: r.title })}
                        aria-disabled={busy || undefined}
                        onClick={() => void skip([r])}
                      >
                        {t('admin.aiCheck.skip')}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollBox>
      )}
      <AdminDialog
        open={confirm}
        title={t('admin.aiCheck.confirm', { count: waiting.length })}
        className="h2-admin__dialog"
        onClose={() => setConfirm(false)}
      >
        <div className="h2-admin__actions">
          <button type="button" className="btn" onClick={() => setConfirm(false)}>
            {t('admin.aiCheck.cancel')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setConfirm(false);
              void skip(waiting);
            }}
          >
            {t('admin.aiCheck.confirmButton')}
          </button>
        </div>
      </AdminDialog>
    </section>
  );
}
