import { screen, waitFor } from '@testing-library/react';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { makeDetail } from '@/test/fixtures';

const ID = '11111111-1111-5111-8111-111111111111';
const RESOURCE = `/resource/${ID}/acme-translator`;
const withAdSense = () =>
  mockApi({
    'GET /site': () => ({
      monetisation: {
        githubSponsorsUrl: null,
        stripePaymentUrl: null,
        ga4MeasurementId: null,
        ethicalAdsPublisher: null,
        adsenseClient: 'ca-pub-4688630163076563',
        adsenseSlot: '1234567890',
      },
    }),
    [`GET /resources/${ID}`]: () => makeDetail(),
  });

function clearGoogle() {
  for (const id of ['h2-adsense', 'h2-googlefc']) document.getElementById(id)?.remove();
  document.querySelector('iframe[name="googlefcPresent"]')?.remove();
  delete window.adsbygoogle;
  delete window.googlefc;
}

beforeEach(clearGoogle);
afterEach(clearGoogle);

describe('Feature: the AdSense slot never sends search words to Google (ADR 0042)', () => {
  it("Scenario: a resource page loads Google's consent tool and the ad", async () => {
    withAdSense();
    renderApp(RESOURCE);
    await waitFor(() => expect(document.getElementById('h2-adsense')).not.toBeNull());
    expect(document.getElementById('h2-googlefc')).not.toBeNull();
    expect(document.querySelector('ins.adsbygoogle')).not.toBeNull();
  });

  it.each(['?q=agents', '?ask=which+one'])(
    'Scenario: a resource page opened with %s loads nothing from Google and shows no ad',
    async (search) => {
      withAdSense();
      renderApp(`${RESOURCE}${search}`);
      await screen.findByRole('heading', { level: 1 });
      await settle();
      expect(document.getElementById('h2-adsense')).toBeNull();
      expect(document.getElementById('h2-googlefc')).toBeNull();
      expect(document.querySelector('ins.adsbygoogle')).toBeNull();
    },
  );
});
