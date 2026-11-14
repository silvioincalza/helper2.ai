import { useTranslation } from 'react-i18next';
import { useTitle } from '@/hooks/useTitle';
import { Robots } from '@/shell/Robots';
import { Saved } from '@/features/saved/Saved';

export default function SavedPage() {
  const { t } = useTranslation();
  useTitle(t('nav.saved'));
  return (
    <>
      <Robots content="noindex, follow" />
      <Saved />
    </>
  );
}
