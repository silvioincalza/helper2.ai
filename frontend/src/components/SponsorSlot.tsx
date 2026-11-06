import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useMonetisation } from '@/context/MonetisationContext';
import '@/components/sponsor.css';

const SCRIPT_ID = 'h2-ethicalads';
const SCRIPT_SRC = 'https://media.ethicalads.io/media/client/ethicalads.min.js';

declare global {
  interface Window {
    ethicalads?: { load: () => void };
  }
}

/** The EthicalAds client's marks on a filled box, cleared so its load() fills the box again. */
type FilledBox = HTMLDivElement & { _ea_dark_observer?: { disconnect: () => void } | null };

/**
 * A fresh ad for this box. The client's reload() skips manual placements, and load() skips a box marked "loaded",
 * so the box is emptied first and then loaded with force (load()).
 */
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

/**
 * The one ad slot of a results page (ADR 0057; EthicalAds allows one ad per page). It sits above the results, in a
 * box of fixed height, so nothing moves when the ad comes or does not. While a search runs it also shows the
 * progress bar and why there is an ad. It never holds the results back: they replace the bar the moment they come.
 * A new search empties the box and asks for a new ad. Off while the server has no publisher ID.
 */
export function SponsorSlot({ query, loading }: { query: string; loading: boolean }) {
  const { t } = useTranslation();
  const { ethicalAdsPublisher } = useMonetisation();
  const box = useRef<FilledBox>(null);

  useEffect(() => {
    if (!ethicalAdsPublisher || !query) return;
    loadClient(() => {
      if (box.current) freshAd(box.current);
    });
  }, [ethicalAdsPublisher, query]);

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
      {/* EthicalAds fills this box; the box stays mounted between searches (its client asks for that). */}
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
