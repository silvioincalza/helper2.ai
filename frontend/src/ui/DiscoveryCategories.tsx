import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';

export const CATEGORY_NOTICES: Record<string, 'health' | 'legal'> = {
  health: 'health',
  legal: 'legal',
};

export function categoryOf(source: SourceInfo): string {
  return source.id.replace(/^discovery-/, '');
}

export function CategoryNotice({ category }: { category: string }) {
  const { t } = useTranslation();
  const notice = CATEGORY_NOTICES[category];
  if (!notice) return null;
  return <span className="h2-sub h2-src-notice">{t(`sourcesPage.notice.${notice}`)}</span>;
}
