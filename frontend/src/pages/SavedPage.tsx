import { useTranslation } from 'react-i18next';
import { useTitle } from '@/hooks/useTitle';
import { Saved } from '@/components/saved/Saved';

export default function SavedPage() {
  const { t } = useTranslation();
  useTitle(t('nav.saved'));
  return <Saved />;
}
