import type { ReactNode, Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { Icon } from '../Icon';
import { Heading } from './Heading';
import './ui.css';

/**
 * A designed state (ADR 0040, 8): a heading that says what happened, [children] that say why, and the [next] step.
 * An empty home board has no next step: the box above it is one. An error is an alert, so a screen reader hears it
 * when it appears; a notice (nothing found, no page here) is read with the page. The heading takes focus from
 * [headingRef] (Try again, see useRetry).
 */
export function StatePanel({ tone, title, level, headingRef, children, next }: {
  tone: 'error' | 'notice';
  title: string;
  level: number;
  headingRef?: Ref<HTMLHeadingElement>;
  children?: ReactNode;
  next?: ReactNode;
}) {
  return (
    <div role={tone === 'error' ? 'alert' : undefined} className={`h2-state h2-state--${tone}`}>
      <Heading level={level} ref={headingRef} tabIndex={-1} className="h2-state__title">
        {tone === 'error' && <Icon name="warn" />}{title}
      </Heading>
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
