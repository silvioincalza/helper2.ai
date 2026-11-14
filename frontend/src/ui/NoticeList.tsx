import { useTranslation } from 'react-i18next';
import type { Notice } from '@/api/types';
import { Icon } from '@/ui/Icon';

type NoticeCode = Notice['code'];

function visibleNotices(notices: readonly Notice[], exclude: readonly NoticeCode[] = []): Notice[] {
  return notices.filter((n) => !exclude.includes(n.code));
}

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
