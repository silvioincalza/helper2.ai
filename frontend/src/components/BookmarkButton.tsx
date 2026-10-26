import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { useBookmarks } from '@/context/BookmarksContext';
import { Icon } from './Icon';

export function BookmarkButton({ card }: { card: Pick<ResultCard, 'id' | 'title' | 'url' | 'type'> }) {
  const { t } = useTranslation();
  const { isSaved, toggle } = useBookmarks();
  const saved = isSaved(card.id);
  return (
    <button
      type="button"
      className={`btn btn-ghost bookmark${saved ? ' is-saved' : ''}`}
      aria-pressed={saved}
      aria-label={t('bookmark.label', { title: card.title })}
      onClick={() => toggle({ resourceId: card.id, title: card.title, url: card.url, type: card.type })}
    >
      <Icon name="bookmark" />
      {saved ? t('bookmark.saved') : t('bookmark.save')}
    </button>
  );
}
