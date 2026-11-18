import { useTranslation } from 'react-i18next';
import { AdminEmpty } from './AdminHead';

export function ReviewQueueEmpty({
  command,
  onClearResearchFilter,
}: {
  command: string;
  onClearResearchFilter: (() => void) | undefined;
}) {
  const { t } = useTranslation();
  if (!onClearResearchFilter) return <AdminEmpty command={command} text={t('admin.queueEmpty')} />;
  return (
    <AdminEmpty
      command={command}
      text={t('admin.research.filterEmpty')}
      next={
        <button type="button" className="btn btn-sm" onClick={onClearResearchFilter}>
          {t('admin.research.filterClear')}
        </button>
      }
    />
  );
}
