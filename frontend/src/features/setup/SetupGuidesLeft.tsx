import { useTranslation } from 'react-i18next';
import { Icon } from '@/ui/Icon';
import { useMe } from '@/context/MeContext';
import { clockTime } from '@/lib/format';

const TEST_CEILING = 100;

export interface GuidesLeft {
  text: string;
  none: boolean;
}

export function useSetupGuidesLeft(): GuidesLeft | null {
  const { t } = useTranslation();
  const { limits } = useMe().me;
  const allowance = limits?.setupGuides;
  if (!limits || !allowance || allowance.limit === 0) return null;
  const { limit, remaining } = allowance;
  if (remaining === 0)
    return {
      none: true,
      text: t('setup.guidesLeft.none', { time: clockTime(limits.resetsAt, false) }),
    };
  if (limit >= TEST_CEILING) return null;
  return { none: false, text: t('setup.guidesLeft.some', { remaining, limit }) };
}

export function SetupGuidesLeft({ id, value }: { id: string; value: GuidesLeft }) {
  return (
    <p id={id} className={value.none ? 'small setup-left' : 'muted small setup-left'}>
      <Icon name="info" /> {value.text}
    </p>
  );
}
