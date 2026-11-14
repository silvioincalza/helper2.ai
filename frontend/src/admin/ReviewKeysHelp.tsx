import './adminText';
import { useTranslation } from 'react-i18next';
import { AdminDialog } from './AdminDialog';

const REVIEW_KEYS = ['j', 'k', 'x', 'a', 'r', 'n', '?'] as const;

export function ReviewKeysHelp({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <AdminDialog
      open={open}
      title={t('admin.keys.title')}
      className="h2-admin__keys"
      onClose={onClose}
    >
      <dl>
        {REVIEW_KEYS.map((k) => (
          <div key={k}>
            <dt>
              <kbd>{k}</kbd>
            </dt>
            <dd>{t(`admin.keys.${k === '?' ? 'help' : k}`)}</dd>
          </div>
        ))}
      </dl>
      <p className="muted">{t('admin.keys.where')}</p>
      <button type="button" className="btn" onClick={onClose}>
        {t('admin.keys.close')}
      </button>
    </AdminDialog>
  );
}
