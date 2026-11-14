import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api } from '@/api/client';
import type { Monetisation } from '@/api/types';

const MONETISATION_OFF: Monetisation = {
  githubSponsorsUrl: null,
  stripePaymentUrl: null,
  ga4MeasurementId: null,
  ethicalAdsPublisher: null,
  adsenseClient: null,
  adsenseSlot: null,
};

const MonetisationContext = createContext<Monetisation>(MONETISATION_OFF);

export function MonetisationProvider({ children }: { children: ReactNode }) {
  const [monetisation, setMonetisation] = useState(MONETISATION_OFF);

  useEffect(() => {
    const ac = new AbortController();
    api.getSite(ac.signal).then(
      (site) => setMonetisation(site.monetisation),
      () => undefined,
    );
    return () => ac.abort();
  }, []);

  return (
    <MonetisationContext.Provider value={monetisation}>{children}</MonetisationContext.Provider>
  );
}

export function useMonetisation(): Monetisation {
  return useContext(MonetisationContext);
}
