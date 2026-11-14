import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import './term.css';

export function ScrollBox({ label, children }: { label: string; children: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div
      className="h2-scroll"
      role="region"
      aria-label={t('lists.scrollBox', { name: label })}
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
      tabIndex={0}
    >
      {children}
    </div>
  );
}
