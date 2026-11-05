import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import './term.css';

/**
 * A wide table in its own box: on a phone it scrolls sideways inside the box, the page does not. The box is a named
 * region a keyboard can reach, so the arrow keys scroll it.
 */
export function ScrollBox({ label, children }: { label: string; children: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div
      className="v4-scroll"
      role="region"
      aria-label={t('lists.scrollBox', { name: label })}
      // A box that scrolls must be reachable by keyboard (WCAG 2.1.1, axe scrollable-region-focusable).
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
      tabIndex={0}
    >
      {children}
    </div>
  );
}
