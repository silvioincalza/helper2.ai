import { useTranslation } from 'react-i18next';
import { useTitle } from '@/hooks/useTitle';
import { Robots } from '@/ui/Robots';
import { Saved } from './Saved';

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
