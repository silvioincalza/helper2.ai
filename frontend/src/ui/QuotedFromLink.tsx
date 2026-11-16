import { useTranslation } from 'react-i18next';
import { displayHost, safeHref } from '@/lib/format';

/**
 * The page where a checked quote can be read (ADR 0031). It can be another page than the result's own link: a
 * package's text shown under its repository names the package page. Nothing when the server named no page.
 */
export function QuotedFromLink({
  pick,
}: {
  pick: { quotedFrom: { url: string } | null } | undefined;
}) {
  const { t } = useTranslation();
  const href = pick?.quotedFrom ? safeHref(pick.quotedFrom.url) : null;
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
