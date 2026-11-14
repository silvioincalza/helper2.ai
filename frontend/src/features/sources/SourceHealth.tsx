import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';
import { clockTime } from '@/lib/format';
import { creditsOf, pauseOf, responseTimeLabel } from '@/lib/sourceHealth';

function ResponseTime({ ms }: { ms: number }) {
  const { i18n } = useTranslation();
  const label = responseTimeLabel(ms, i18n.language);
  return (
    <>
      <span aria-hidden="true">{label.short}</span>
      <span className="sr-only">{label.long}</span>
    </>
  );
}

export function ResponseTimeValue({ source }: { source: SourceInfo }) {
  const { t } = useTranslation();
  return source.responseTimeMs != null ? (
    <ResponseTime ms={source.responseTimeMs} />
  ) : (
    <>{t('sourcesPage.noCalls')}</>
  );
}

export function WebCreditsValue({ source }: { source: SourceInfo }) {
  const { t, i18n } = useTranslation();
  const credits = creditsOf(source);
  if (!credits) return null;
  const n = (v: number) => new Intl.NumberFormat(i18n.language).format(v);
  if (credits.kind === 'unavailable') return <>{t('sourcesPage.credits.unavailable')}</>;
  if (credits.kind === 'uncapped')
    return <>{t('sourcesPage.credits.uncapped', { used: n(credits.used) })}</>;
  return <>{t('sourcesPage.credits.capped', { used: n(credits.used), cap: n(credits.cap) })}</>;
}

export function SourceNotes({ source }: { source: SourceInfo }) {
  const { t } = useTranslation();
  const pause = pauseOf(source);
  if (!pause) return null;
  const text =
    pause.kind === 'trying'
      ? t('sourcesPage.pause.trying')
      : pause.since
        ? t('sourcesPage.pause.since', { time: clockTime(pause.since) })
        : t('sourcesPage.pause.paused');
  return <p className={`h2-src-card__note is-${pause.kind}`}>{text}</p>;
}
