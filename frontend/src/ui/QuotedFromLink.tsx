import { useTranslation } from 'react-i18next';
import { displayHost, safeHref } from '@/lib/format';

/**
 * The page where a checked quote can be read (ADR 0031): of a pick, a comparison cell or an explanation sentence.
 * It can be another page than the resource's own link: a package's text shown under its repository names the
 * package page. Nothing when the server named no page.
 */
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
