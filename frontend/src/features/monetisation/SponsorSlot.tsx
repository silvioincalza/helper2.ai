import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useMonetisation } from '@/context/MonetisationContext';
import '@/features/monetisation/sponsor.css';

const SCRIPT_ID = 'h2-ethicalads';
const SCRIPT_SRC = 'https://media.ethicalads.io/media/client/ethicalads.min.js';

declare global {
  interface Window {
    ethicalads?: { load: () => void };
  }
}

type FilledBox = HTMLDivElement & { _ea_dark_observer?: { disconnect: () => void } | null };

function freshAd(box: FilledBox) {
  box.innerHTML = '';
  box.classList.remove('loaded');
  box._ea_dark_observer?.disconnect();
  box._ea_dark_observer = null;
  window.ethicalads?.load();
}

function loadClient(onReady: () => void) {
  if (window.ethicalads) {
    onReady();
    return;
  }
  const existing = document.getElementById(SCRIPT_ID);
  const script = existing ?? document.createElement('script');
  script.addEventListener('load', onReady, { once: true });
  if (!existing) {
    script.id = SCRIPT_ID;
    (script as HTMLScriptElement).async = true;
    (script as HTMLScriptElement).src = SCRIPT_SRC;
    document.head.appendChild(script);
  }
}

export function SponsorSlot({ query, loading }: { query: string; loading: boolean }) {
  const { t } = useTranslation();
  const { ethicalAdsPublisher } = useMonetisation();
  const box = useRef<FilledBox>(null);
  const adFor = useRef('');

  useEffect(() => {
    if (!ethicalAdsPublisher || !query || loading || adFor.current === query) return;
    adFor.current = query;
    loadClient(() => {
      if (box.current && adFor.current === query) freshAd(box.current);
    });
  }, [ethicalAdsPublisher, query, loading]);

  if (!ethicalAdsPublisher) return null;
  return (
    <aside className="h2-sponsor" aria-label={t('sponsor.label')}>
      <p className="h2-sponsor__head">
        <span className="h2-sponsor__tag">{t('sponsor.tag')}</span>
        <span className="h2-sponsor__why">{t('sponsor.why')}</span>
      </p>
      {loading && (
        <div
          className="h2-sponsor__progress"
          role="progressbar"
          aria-label={t('sponsor.searching')}
        >
          <span className="h2-sponsor__bar" />
        </div>
      )}
      <div
        ref={box}
        className="h2-sponsor__ad horizontal"
        data-ea-publisher={ethicalAdsPublisher}
        data-ea-type="image"
        data-ea-manual="true"
        id="h2-results-ad"
      />
    </aside>
  );
}
