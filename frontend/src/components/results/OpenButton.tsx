import { useTranslation } from 'react-i18next';
import { safeHref } from '@/lib/format';

/**
 * The main "Open on <host>" button of a result. The URL comes from a source, so it goes through safeHref: a
 * javascript:, data: or other non-web link shows no button at all.
 */
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
    </a>
  );
}
