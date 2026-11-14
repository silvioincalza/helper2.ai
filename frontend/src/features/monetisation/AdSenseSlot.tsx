import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useMonetisation } from '@/context/MonetisationContext';
import { loadGoogleConsent } from '@/lib/googleConsent';
import '@/features/monetisation/sponsor.css';

const SCRIPT_ID = 'h2-adsense';

declare global {
  interface Window {
    adsbygoogle?: unknown[] & { pauseAdRequests?: number };
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

export function AdSenseSlot() {
  const { t } = useTranslation();
  const { adsenseClient, adsenseSlot } = useMonetisation();

  useEffect(() => {
    if (!adsenseClient || !adsenseSlot) return;
    const queue = (window.adsbygoogle = window.adsbygoogle ?? []);
    queue.pauseAdRequests = 0;
    loadGoogleConsent(adsenseClient);
    loadAdSense(adsenseClient);
    try {
      queue.push({});
    } catch {}
    return () => {
      queue.pauseAdRequests = 1;
    };
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
