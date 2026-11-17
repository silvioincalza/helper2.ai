import { useTranslation } from 'react-i18next';
import { displayHost, safeHref } from '@/lib/format';

export function QuotedFromLink({ from }: { from: { url: string } | null | undefined }) {
  const { t } = useTranslation();
  const href = from ? safeHref(from.url) : null;
  if (!href) return null;
  return (
    <>
      {' · '}
      <a href={href} target="_blank" rel="noopener noreferrer nofollow ugc">
        {t('results.quotedFrom', { host: displayHost(href) })} <span aria-hidden="true">↗</span>{' '}
        <span className="sr-only">{t('common.opensNewTab')}</span>
      </a>
    </>
  );
}
