import { act, screen, waitFor, within } from '@testing-library/react';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { makeSearch } from '@/test/fixtures';
import { SUPPORT_WAIT_DELAY_MS } from './SupportWhileSearching';
import type { Monetisation } from '@/api/types';

const OFF: Monetisation = {
  githubSponsorsUrl: null,
  stripePaymentUrl: null,
  ga4MeasurementId: null,
  adsenseClient: null,
  adsenseSlot: null,
  adsenseSlotSearch: null,
};
const SUPPORT: Monetisation = { ...OFF, githubSponsorsUrl: 'https://github.com/sponsors/someone' };
const site = (monetisation: Monetisation) => () => ({ monetisation });
const pause = (ms: number) => act(() => new Promise<void>((r) => setTimeout(r, ms)));
const note = () => screen.queryByRole('complementary', { name: 'From helper2.ai' });

function slowSearch() {
  let answer: (() => void) | undefined;
  const handler = () =>
    new Promise((resolve) => {
      answer = () => resolve(makeSearch());
    });
  return { handler, finish: () => answer?.() };
}

describe('Feature: a note from helper2.ai while a search loads (ADR 0042)', () => {
  it('Scenario: a slow first search shows the note with a link to Support, and the results replace it', async () => {
    const search = slowSearch();
    mockApi({ 'GET /site': site(SUPPORT), 'GET /search': search.handler });
    renderApp('/search?q=agents');

    const box = await screen.findByRole(
      'complementary',
      { name: 'From helper2.ai' },
      { timeout: SUPPORT_WAIT_DELAY_MS + 2000 },
    );
    expect(box).toHaveTextContent('If it saves you time, you can help pay for it.');
    expect(within(box).getByRole('link', { name: 'Support helper2.ai' })).toHaveAttribute(
      'href',
      '/support',
    );

    await act(async () => search.finish());
    await waitFor(() => expect(note()).not.toBeInTheDocument());
  });

  it('Scenario: without a support link there is no note', async () => {
    const search = slowSearch();
    mockApi({ 'GET /site': site(OFF), 'GET /search': search.handler });
    renderApp('/search?q=agents');
    await pause(SUPPORT_WAIT_DELAY_MS + 300);
    expect(note()).not.toBeInTheDocument();
  });

  it('Scenario: a search faster than the delay never shows the note', async () => {
    mockApi({ 'GET /site': site(SUPPORT), 'GET /search': () => makeSearch() });
    renderApp('/search?q=agents');
    await settle();
    await pause(SUPPORT_WAIT_DELAY_MS + 300);
    expect(note()).not.toBeInTheDocument();
  });

  it('Scenario: a guide question shows no note', async () => {
    const search = slowSearch();
    mockApi({ 'GET /site': site(SUPPORT), 'GET /search': search.handler });
    renderApp('/search?q=which+agent+framework&ask=which+agent+framework');
    await pause(SUPPORT_WAIT_DELAY_MS + 300);
    expect(note()).not.toBeInTheDocument();
  });
});
