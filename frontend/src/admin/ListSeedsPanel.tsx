import './adminText';
import { useCallback, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import type { ListSeedEntry } from '@/api/types';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';
import { TableSkeleton } from '@/ui/Skeleton';
import { trimOrNull } from './adminModel';
import { useAdminAction } from './useAdminAction';
import { useFocusReturn } from './useFocusReturn';

type Done = { kind: 'added'; address: string } | { kind: 'removed'; address: string };

const FALLBACK = 'lists';

export function ListSeedsPanel() {
  const { t } = useTranslation();
  const seeds = useApi('admin/list-seeds', (s) => adminApi.listListSeeds(s), { cache: false });
  const [address, setAddress] = useState('');
  const [reason, setReason] = useState('');
  const [empty, setEmpty] = useState(false);
  const [removing, setRemoving] = useState<ListSeedEntry | null>(null);
  const [removeReason, setRemoveReason] = useState('');
  const { busy, notice, clearNotice, run } = useAdminAction<Done>(seeds.reload);
  const focusOnMount = useCallback((el: HTMLElement | null) => el?.focus(), []);
  const focus = useFocusReturn(removing !== null || seeds.loading);
  const closeRemove = (entry: ListSeedEntry) => {
    focus.returnTo(entry.id, FALLBACK);
    setRemoving(null);
  };

  const loaded = seeds.data ?? seeds.previous;
  const all = loaded?.lists ?? [];

  const add = (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (address.trim() === '') {
      setEmpty(true);
      return;
    }
    setEmpty(false);
    void run(async () => {
      const saved = await adminApi.addListSeed({
        address: address.trim(),
        reason: trimOrNull(reason),
      });
      setAddress('');
      setReason('');
      return { kind: 'added', address: saved.address };
    });
  };

  const confirmRemove = () => {
    if (!removing) return;
    const gone = removing;
    void run(async () => {
      try {
        await adminApi.removeListSeed(gone.id, trimOrNull(removeReason));
        return { kind: 'removed', address: gone.address };
      } finally {
        closeRemove(gone);
      }
    });
  };

  const done = notice?.kind === 'added' || notice?.kind === 'removed';

  return (
    <section aria-labelledby="ls-h">
      <h3 id="ls-h" className="h2-admin__h">
        {t('admin.settings.lists.title')}
      </h3>
      <p className="muted">{t('admin.settings.lists.lede')}</p>
      {notice && (
        <p role={done ? 'status' : 'alert'} className={done ? 'banner' : 'banner banner-warn'}>
          {notice.kind === 'added'
            ? t('admin.settings.lists.added', { address: notice.address })
            : notice.kind === 'removed'
              ? t('admin.settings.lists.removed', { address: notice.address })
              : notice.kind === 'refused'
                ? t('admin.settings.lists.refused', { detail: notice.detail })
                : t('admin.settings.lists.failed')}
        </p>
      )}
      <form className="h2-admin__form" noValidate onSubmit={add} aria-labelledby="ls-add-h">
        <h4 id="ls-add-h" className="h2-admin__h">
          {t('admin.settings.lists.addTitle')}
        </h4>
        <label>
          {t('admin.settings.lists.address')}
          <input
            type="text"
            inputMode="url"
            autoComplete="off"
            spellCheck={false}
            placeholder="owner/awesome-llm or https://…"
            maxLength={300}
            value={address}
            aria-invalid={empty}
            aria-describedby={empty ? 'ls-err' : undefined}
            onChange={(e) => setAddress(e.target.value)}
          />
        </label>
        {empty && (
          <p id="ls-err" role="alert" className="banner banner-warn">
            {t('admin.settings.lists.empty')}
          </p>
        )}
        <label>
          {t('admin.settings.lists.reason')}
          <input
            type="text"
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="h2-admin__tools">
          <button type="submit" className="btn btn-primary" aria-disabled={busy || undefined}>
            {t('admin.settings.lists.add')}
          </button>
        </div>
      </form>
      {removing && (
        <div className="h2-admin__form" role="group" aria-labelledby="ls-review-h">
          <h4 id="ls-review-h" ref={focusOnMount} tabIndex={-1} className="h2-admin__h">
            {t('admin.settings.lists.removeTitle', { address: removing.address })}
          </h4>
          <p>{t('admin.settings.lists.removeEffect')}</p>
          <label>
            {t('admin.settings.lists.reason')}
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
              {t('admin.settings.lists.confirmRemove')}
            </button>
            <button
              type="button"
              className="btn"
              aria-disabled={busy || undefined}
              onClick={() => !busy && closeRemove(removing)}
            >
              {t('admin.settings.lists.cancel')}
            </button>
          </div>
        </div>
      )}
      {seeds.loading && loaded === undefined ? (
        <TableSkeleton rows={3} quiet />
      ) : seeds.error !== undefined && loaded === undefined ? (
        <StatePanel
          tone="error"
          level={4}
          title={t('admin.settings.lists.loadError')}
          next={<RetryButton onClick={seeds.reload} />}
        />
      ) : (
        <section aria-labelledby="ls-list-h">
          <h4 id="ls-list-h" ref={focus.fallback(FALLBACK)} tabIndex={-1} className="h2-admin__h">
            {t('admin.settings.lists.listTitle', { count: all.length, max: loaded?.max ?? 0 })}
          </h4>
          {all.length === 0 ? (
            <p className="muted">{t('admin.settings.lists.none')}</p>
          ) : (
            <div className="table-wrap">
              <table aria-labelledby="ls-list-h">
                <thead>
                  <tr>
                    <th scope="col">{t('admin.settings.lists.col.list')}</th>
                    <th scope="col">{t('admin.settings.lists.col.added')}</th>
                    <th scope="col">{t('admin.settings.lists.col.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {all.map((s) => (
                    <tr key={s.id}>
                      <th scope="row">
                        <code>{s.address}</code>
                      </th>
                      <td>
                        {t(`admin.settings.lists.kind.${s.kind}`)}
                        {' · '}
                        {t('admin.settings.lists.addedBy', {
                          who: s.addedBy,
                          at: formatDate(s.addedAt, 'INSTANT'),
                        })}
                        {s.reason && <span className="muted"> · {s.reason}</span>}
                      </td>
                      <td>
                        <button
                          ref={focus.row(s.id)}
                          type="button"
                          className="btn"
                          aria-disabled={busy || undefined}
                          aria-label={t('admin.settings.lists.removeLabel', {
                            address: s.address,
                          })}
                          onClick={() => {
                            if (busy) return;
                            setRemoving(s);
                            setRemoveReason('');
                            clearNotice();
                          }}
                        >
                          {t('admin.settings.lists.remove')}
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
