import { useTranslation } from 'react-i18next';
import { useTitle } from '@/hooks/useTitle';
import { Robots } from '@/components/Robots';
import { Saved } from '@/components/saved/Saved';

export default function SavedPage() {
  const { t } = useTranslation();
  useTitle(t('nav.saved'));
  // A personal page: nothing in it for a search engine.
  return (
    <>
      <Robots content="noindex, follow" />
      <Saved />
    </>
  );
}
