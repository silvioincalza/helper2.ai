import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useSources } from '@/context/SourcesContext';

/** One line under the home box: how many disclosed sources Helper2.ai searches, linking to their status and terms. */
export function SourceStrip() {
  const { t } = useTranslation();
  const sources = useSources();
  const count = (sources.data ?? []).filter((s) => s.enabled).length;
  if (count === 0) return null;
  return (
    <p className="source-strip">
      <Link to="/sources">{t('home.sourcesLine', { count })} <span aria-hidden="true">→</span></Link>
    </p>
  );
}
