import { useMe } from '@/context/MeContext';
import type { Monetisation } from '@/api/types';

/** Everything off: what the page knows before GET /me answers, when it fails, and on the admin host. */
const MONETISATION_OFF: Monetisation = {
  githubSponsorsUrl: null,
  stripePaymentUrl: null,
  ga4MeasurementId: null,
  ethicalAdsPublisher: null,
  adsenseClient: null,
  adsenseSlot: null,
};

/**
 * The monetisation parts that are on (ADR 0030), from GET /me, which every page already reads: no extra request,
 * and Home stays without GET /policy (ADR 0028 (part was 0042), 13).
 */
export function useMonetisation(): Monetisation {
  return useMe().me.monetisation ?? MONETISATION_OFF;
}
