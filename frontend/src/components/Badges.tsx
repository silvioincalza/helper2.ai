import { useTranslation } from 'react-i18next';
import type { SourceMode, ValueOrigin, Verification } from '@/api/types';
import { Icon, type IconName } from './Icon';

export function ModeBadge({ mode }: { mode: SourceMode }) {
  const { t } = useTranslation();
  return (
    <span className={`badge mode mode-${mode.toLowerCase()}`} title={t(`mode.${mode}.hint`)}>
      {t(`mode.${mode}.label`)}
    </span>
  );
}

export function OriginLabel({ origin }: { origin: ValueOrigin }) {
  const { t } = useTranslation();
  return <span className="origin">{t(`origin.${origin}`)}</span>;
}

const VERIF_ICON: Record<Verification, IconName> = {
  VERIFIED: 'check',
  UNVERIFIED: 'question',
  CONFLICTING: 'warn',
  REJECTED: 'cross',
};

export function VerificationBadge({ verification }: { verification: Verification }) {
  const { t } = useTranslation();
  return (
    <span className={`badge verif verif-${verification.toLowerCase()}`}>
      <Icon name={VERIF_ICON[verification]} />
      {t(`verification.${verification}`)}
    </span>
  );
}
