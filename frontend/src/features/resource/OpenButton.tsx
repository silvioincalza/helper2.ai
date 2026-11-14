import { useTranslation } from 'react-i18next';
import { safeHref } from '@/lib/format';

export function OpenButton({ url, host }: { url: string; host: string }) {
  const { t } = useTranslation();
  const href = safeHref(url);
  if (!href) return null;
  return (
    <a
      className="btn btn-primary"
      href={href}
      target="_blank"
      rel="noopener noreferrer nofollow ugc"
    >
      {t('results.openOn', { host })} <span aria-hidden="true">↗</span>
      <span className="sr-only"> {t('common.opensNewTab')}</span>
    </a>
  );
}
