import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';

/** Categories with a notice of their own on their row and on their candidates (health, legal; ADR 0051). */
export const CATEGORY_NOTICES: Record<string, 'health' | 'legal'> = {
  health: 'health',
  legal: 'legal',
};

/** The category id of a discovery source: `discovery-coding` is `coding`. */
export function categoryOf(source: SourceInfo): string {
  return source.id.replace(/^discovery-/, '');
}

/** The notice a category's tools come with: not medical or legal advice. Nothing for the other categories. */
export function CategoryNotice({ category }: { category: string }) {
  const { t } = useTranslation();
  const notice = CATEGORY_NOTICES[category];
  if (!notice) return null;
  return <span className="v4-sub h2-src-notice">{t(`sourcesPage.notice.${notice}`)}</span>;
}
