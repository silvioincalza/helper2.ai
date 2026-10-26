import { useTranslation } from 'react-i18next';

export function Pagination({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const { t } = useTranslation();
  const pages = Math.min(Math.max(1, Math.ceil(total / size)), 51);
  if (pages <= 1) return null;
  return (
    <nav className="pagination" aria-label={t('pagination.label')}>
      <button type="button" className="btn" disabled={page <= 0} onClick={() => onPage(page - 1)}>{t('pagination.prev')}</button>
      <span aria-live="polite">{t('pagination.status', { page: page + 1, pages })}</span>
      <button type="button" className="btn" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>{t('pagination.next')}</button>
    </nav>
  );
}
