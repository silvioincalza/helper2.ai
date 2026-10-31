import { useLayoutEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { Bookmark } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { Icon } from '@/components/Icon';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { TypeTag } from '@/components/ui/TypeTag';
import { useBookmarks } from '@/context/BookmarksContext';
import { useRetry } from '@/hooks/useRetry';
import { useTitle } from '@/hooks/useTitle';
import { formatDate, hostname } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';

/**
 * The visitor's saved resources (ADR 0023, ADR 0040): from this browser for a guest, from the account when signed in.
 * Each row reads like a result row and links to its resource page. After Remove, focus goes to the Remove button of
 * the row that took its place (or the one above), or to the empty state's heading, and a status line says what went.
 */
export default function SavedPage() {
  const { t } = useTranslation();
  const b = useBookmarks();
  const { retry, focusRef } = useRetry(b.reload, b.loading);
  const [removed, setRemoved] = useState('');
  const list = useRef<HTMLUListElement>(null);
  const emptyHeading = useRef<HTMLHeadingElement>(null);
  const focusRow = useRef<number | null>(null);
  useTitle(t('nav.saved'));

  useLayoutEffect(() => {
    const index = focusRow.current;
    if (index === null) return;
    focusRow.current = null;
    const buttons = list.current?.querySelectorAll<HTMLButtonElement>('.saved-row__remove');
    if (buttons?.length) buttons[Math.min(index, buttons.length - 1)]?.focus();
    else emptyHeading.current?.focus();
  });

  const remove = (item: Bookmark, index: number) => {
    focusRow.current = index;
    setRemoved(t('saved.removed', { title: item.title }));
    b.remove(item.resourceId);
  };

  return (
    <div className="page">
      <h1 ref={focusRef} tabIndex={-1}>{t('saved.title')}</h1>
      <p className="lede">{b.mode === 'server' ? t('saved.serverNote') : t('saved.localNote')}</p>
      <p role="status" className="sr-only">{removed}</p>
      {b.storageFailed && <p role="alert" className="warn-line"><Icon name="warn" /> {t('saved.storageFailed')}</p>}
      {b.syncFailed && <p role="alert" className="warn-line"><Icon name="warn" /> {t('saved.syncFailed')}</p>}
      {b.loading && (
        <div aria-busy="true">
          <span className="sr-only">{t('common.loading')}</span>
          {[0, 1, 2].map((i) => <div key={i} className="skeleton-row result-skeleton" aria-hidden="true" />)}
        </div>
      )}
      {!b.loading && b.listFailed && (
        <StatePanel tone="error" level={2} title={t('saved.listFailed')} next={<RetryButton onClick={retry} />}>
          <p>{t('saved.listFailedBody')}</p>
        </StatePanel>
      )}
      {!b.loading && !b.listFailed && b.items.length === 0 && (
        <StatePanel tone="notice" level={2} title={t('saved.empty')} headingRef={emptyHeading}
          next={<Link to="/" className="btn btn-primary">{t('saved.search')}</Link>}>
          <p>{t('saved.emptyBody')}</p>
        </StatePanel>
      )}
      {!b.loading && b.items.length > 0 && (
        <ul ref={list} className="result-list" aria-label={t('saved.title')}>
          {b.items.map((item, i) => <SavedRow key={item.resourceId} item={item} onRemove={() => remove(item, i)} />)}
        </ul>
      )}
    </div>
  );
}

function SavedRow({ item, onRemove }: { item: Bookmark; onRemove: () => void }) {
  const { t, i18n } = useTranslation();
  return (
    <li className="result-row saved-row">
      <p className="result-row__meta">
        {item.type && <TypeTag type={item.type} />}
        <span className="result-row__domain">{hostname(item.url)}</span>
        <span>{t('saved.savedAt', { date: formatDate(item.savedAt, i18n.language) })}</span>
      </p>
      <h2 className="result-row__title"><Link to={resourcePath(item.resourceId)}>{item.title}</Link></h2>
      <div className="saved-row__actions">
        <ExternalLink url={item.url} showHost={false}>{t('preview.open')}</ExternalLink>
        <button type="button" className="btn saved-row__remove" aria-label={t('saved.removeLabel', { title: item.title })} onClick={onRemove}>
          {t('saved.remove')}
        </button>
      </div>
    </li>
  );
}
