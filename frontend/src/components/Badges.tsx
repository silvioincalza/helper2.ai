import { useTranslation } from 'react-i18next';
import type { Freshness, SourceMode, SourceOutcome, ValueOrigin, Verification } from '@/api/types';
import { Icon } from './Icon';

const OUTCOME_ICON: Record<SourceOutcome, string> = {
  SUCCEEDED: 'check', FAILED: 'cross', TIMED_OUT: 'clock', RATE_LIMITED: 'pause', SKIPPED: 'minus',
};

export function OutcomeBadge({ outcome }: { outcome: SourceOutcome }) {
  const { t } = useTranslation();
  return (
    <span className={`badge outcome outcome-${outcome.toLowerCase()}`}>
      <Icon name={OUTCOME_ICON[outcome]} />
      {t(`outcome.${outcome}`)}
    </span>
  );
}

export function ModeBadge({ mode }: { mode: SourceMode }) {
  const { t } = useTranslation();
  return (
    <span className={`badge mode mode-${mode.toLowerCase()}`} title={t(`mode.${mode}.hint`)}>
      {t(`mode.${mode}.label`)}
    </span>
  );
}

export function FreshnessBadge({ freshness }: { freshness: Freshness }) {
  const { t } = useTranslation();
  return (
    <span className={`badge freshness freshness-${freshness.toLowerCase()}`}>
      {freshness === 'STALE' && <Icon name="warn" />}
      {t(`freshness.${freshness}`)}
    </span>
  );
}

export function OriginLabel({ origin }: { origin: ValueOrigin }) {
  const { t } = useTranslation();
  return <span className="origin">{t(`origin.${origin}`)}</span>;
}

const VERIF_ICON: Record<Verification, string> = { VERIFIED: 'check', UNVERIFIED: 'question', CONFLICTING: 'warn', REJECTED: 'cross' };

export function VerificationBadge({ verification }: { verification: Verification }) {
  const { t } = useTranslation();
  return (
    <span className={`badge verif verif-${verification.toLowerCase()}`}>
      <Icon name={VERIF_ICON[verification]} />
      {t(`verification.${verification}`)}
    </span>
  );
}
