import { useTranslation } from 'react-i18next';
import { useTitle } from '@/hooks/useTitle';
import { SavedV2 } from '@/components/saved/SavedV2';

export default function SavedPage() {
  const { t } = useTranslation();
  useTitle(t('nav.saved'));
  return <SavedV2 />;
}
