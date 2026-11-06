import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useMonetisation } from '@/context/MonetisationContext';
import '@/components/sponsor.css';

const SCRIPT_ID = 'h2-adsense';

declare global {
  interface Window {
    adsbygoogle?: unknown[];
  }
}

function loadAdSense(client: string, doc: Document = document) {
  if (doc.getElementById(SCRIPT_ID)) return;
  const script = doc.createElement('script');
  script.id = SCRIPT_ID;
  script.async = true;
  script.crossOrigin = 'anonymous';
  script.src = `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${encodeURIComponent(client)}`;
  doc.head.appendChild(script);
}

/**
 * The one AdSense ad of a resource page (ADR 0059), marked "Sponsored", in a box of fixed height so nothing moves.
 * A resource page has no search words in its address. Off while the server has no publisher and ad unit. A new
 * resource mounts a new box (the page is keyed by id), and each box asks AdSense for one ad. Whether the ad is
 * personalised is Google's call from the visitor's answer in its consent tool.
 */
export function AdSenseSlot() {
  const { t } = useTranslation();
  const { adsenseClient, adsenseSlot } = useMonetisation();

  useEffect(() => {
    if (!adsenseClient || !adsenseSlot) return;
    loadAdSense(adsenseClient);
    try {
      (window.adsbygoogle = window.adsbygoogle ?? []).push({});
    } catch {
      // A box that already has its ad (a second effect run in development): nothing to do.
    }
  }, [adsenseClient, adsenseSlot]);

  if (!adsenseClient || !adsenseSlot) return null;
  return (
    <aside className="h2-sponsor h2-adsense" aria-label={t('sponsor.label')}>
      <p className="h2-sponsor__head">
        <span className="h2-sponsor__tag">{t('sponsor.tag')}</span>
      </p>
      <ins
        className="adsbygoogle h2-adsense__ad"
        data-ad-client={adsenseClient}
        data-ad-slot={adsenseSlot}
        data-ad-format="auto"
        data-full-width-responsive="true"
      />
    </aside>
  );
}
