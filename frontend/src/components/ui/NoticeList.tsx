import { useTranslation } from 'react-i18next';
import type { Notice } from '@/api/types';
import { Icon } from '@/components/Icon';

type NoticeCode = Notice['code'];

/** The notices to show: every notice whose code is not excluded. */
function visibleNotices(notices: readonly Notice[], exclude: readonly NoticeCode[] = []): Notice[] {
  return notices.filter((n) => !exclude.includes(n.code));
}

/**
 * A search's notices, one line each: an icon, the tone for screen readers, and the text localized by code (the
 * server's message when there is no key). Nothing when no notice is left. The text is from the server, never from a
 * model: keep this list outside elements marked as AI-written (ADR 0038).
 */
export function NoticeList({
  notices,
  exclude = [],
}: {
  notices: readonly Notice[];
  exclude?: readonly NoticeCode[];
}) {
  const { t } = useTranslation();
  const shown = visibleNotices(notices, exclude);
  if (shown.length === 0) return null;
  return (
    <ul className="plain-list notices">
      {shown.map((n, i) => {
        const warning = n.code === 'ALL_SOURCES_FAILED';
        return (
          <li key={`${n.code}-${i}`} className={`notice notice-${n.code.toLowerCase()}`}>
            <Icon name={warning ? 'warn' : 'info'} />
            <span className="sr-only">
              {t(warning ? 'results.noticeWarning' : 'results.noticeInfo')}:{' '}
            </span>
            {t(`results.notice.${n.code}`, { defaultValue: n.message })}
          </li>
        );
      })}
    </ul>
  );
}
