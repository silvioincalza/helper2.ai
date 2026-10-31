import type { ReactNode, Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '@/components/Icon';
import './ui.css';

/**
 * A designed state (ADR 0040, 8): a heading that says what happened, [children] that say why, and the [next] step.
 * An empty home board has no next step: the box above it is one. An error is an alert, so a screen reader hears it
 * when it appears. A notice (nothing found, no page here) is read with the page, or announced as a status when it
 * comes after the page loaded ([live]). The heading takes focus from [headingRef] (Try again, see useRetry).
 */
export function StatePanel({ tone, title, level, headingRef, live = false, children, next }: {
  tone: 'error' | 'notice';
  title: string;
  level: number;
  headingRef?: Ref<HTMLHeadingElement> | undefined;
  live?: boolean;
  children?: ReactNode;
  next?: ReactNode;
}) {
  const Tag = `h${Math.min(Math.max(level, 1), 6)}` as 'h2';
  const role = tone === 'error' ? 'alert' : live ? 'status' : undefined;
  return (
    <div role={role} className={`h2-state h2-state--${tone}`}>
      <Tag ref={headingRef} tabIndex={headingRef ? -1 : undefined} className="h2-state__title">
        {tone === 'error' && <Icon name="warn" />}{title}
      </Tag>
      {children}
      {next && <div className="h2-state__next">{next}</div>}
    </div>
  );
}

/** The next step of an error: ask again. */
export function RetryButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation();
  return <button type="button" className="btn btn-primary" onClick={onClick}><Icon name="refresh" /> {t('common.retry')}</button>;
}
