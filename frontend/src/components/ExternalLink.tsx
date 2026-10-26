import { useTranslation } from 'react-i18next';
import { hostname, safeHref } from '@/lib/format';
import { Icon } from './Icon';

export function ExternalLink({ url, children, showHost = true }: { url: string; children?: React.ReactNode; showHost?: boolean }) {
  const { t } = useTranslation();
  const href = safeHref(url);
  if (!href) return <span>{children ?? url}</span>;
  return (
    <a href={href} target="_blank" rel="noopener noreferrer nofollow" className="ext-link">
      {children ?? t('card.openOriginal')}
      {showHost && <span className="host"> {hostname(url)}</span>}
      <Icon name="link" />
      <span className="sr-only"> {t('common.opensNewTab')}</span>
    </a>
  );
}
