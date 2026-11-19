import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FIRST_ROWS } from '@/ui/ListState';

export type ListId = 'news' | 'events';

export function useFirstRows<Row>(rows: Row[]) {
  const [all, setAll] = useState(false);
  return { all, toggle: () => setAll((a) => !a), shown: all ? rows : rows.slice(0, FIRST_ROWS) };
}

export function ShowAll({
  command,
  count,
  all,
  controls,
  onToggle,
}: {
  command: string;
  count: number;
  all: boolean;
  controls: string;
  onToggle: () => void;
}) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      className="h2-cmd"
      aria-expanded={all}
      aria-controls={controls}
      onClick={onToggle}
    >
      <span aria-hidden="true">$ {command} --all · </span>
      {all ? t('lists.showFewer') : t('lists.showAll', { count })}
    </button>
  );
}
