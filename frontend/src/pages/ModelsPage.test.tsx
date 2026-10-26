import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Assertion, ModelFamily } from '@/api/types';
import { makeCard } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { MODELS_ENABLED } from '@/lib/features';

const ev = (url: string) => [{ id: '77777777-7777-5777-8777-777777777777', sourceUrl: url, retrievedAt: '2026-10-27T09:00:00Z', excerpt: 'context window of 1M tokens' }];
const a = (field: string, value: string, url = 'https://docs.example.com/models'): Assertion =>
  ({ field, value, origin: 'CURATED', verification: 'VERIFIED', evidence: ev(url) });

function family(id: string, name: string, specs: Assertion[], over: Partial<ModelFamily> = {}): ModelFamily {
  return { id, provider: name.split(' ')[0] ?? name, name, openWeights: false, card: makeCard({ title: name, url: `https://${id}.example.com/models` }), specs, listedModels: [], ...over };
}

const FAMILIES = [
  family('openai', 'OpenAI GPT', [a('flagship', 'GPT-X'), a('contextTokens', '400000'), a('inputUsdPerMTok', '1.25'), a('benchmark:GPQA Diamond', '88.1')]),
  family('anthropic', 'Anthropic Claude', [a('flagship', 'Claude Y'), a('contextTokens', '1000000'), a('inputUsdPerMTok', '3'), a('benchmark:GPQA Diamond', '87')], {
    listedModels: [{ resourceId: '88888888-8888-5888-8888-888888888888', title: 'anthropic/claude-small', url: 'https://huggingface.co/anthropic/claude-small', sourceId: 'hf-provider-orgs', retrievedAt: '2026-10-27T09:00:00Z', published: '2026-10-07T00:00:00Z' }],
  }),
  family('meta', 'Meta Llama', [a('license', 'llama-4'), a('openWeights', 'true')], { openWeights: true }),
];

// The page is on the roadmap (lib/features.ts); these run again when it comes back.
describe.skipIf(!MODELS_ENABLED)('models page', () => {
  it('compares the default families with sourced specs and marks the best stated value', async () => {
    mockApi({ 'GET /model-families': () => ({ families: FAMILIES }) });
    renderApp('/models');
    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Spec', 'OpenAI GPTOpenAI', 'Anthropic ClaudeAnthropic']);
    const ctx = within(table).getByRole('row', { name: /Context window/ });
    expect(within(ctx).getByText('1M').closest('td')).toHaveClass('spec-best');
    expect(within(ctx).getByText('400K').closest('td')).not.toHaveClass('spec-best');
    const price = within(table).getByRole('row', { name: /Input price/ });
    expect(within(price).getByText('$1.25').closest('td')).toHaveClass('spec-best');
    expect(within(table).getByRole('row', { name: /GPQA Diamond/ })).toBeInTheDocument();
    const src = within(ctx).getAllByRole('link', { name: /source/ })[0];
    expect(src).toHaveAttribute('href', 'https://docs.example.com/models');
    expect(src?.getAttribute('title')).toContain('context window of 1M tokens');
    expect(within(table).getByRole('row', { name: /License/ })).toHaveTextContent('Not stated');
    expect(screen.getByRole('heading', { name: 'Newest Anthropic Claude models found by Scout' })
      .closest('section')).toHaveTextContent('anthropic/claude-small');
  });

  it('explains the sources in a tooltip on the title', async () => {
    mockApi({ 'GET /model-families': () => ({ families: FAMILIES }) });
    renderApp('/models');
    const tip = await screen.findByRole('button', { name: 'About this comparison' });
    expect(screen.queryByRole('tooltip')).toBeNull();
    await userEvent.click(tip);
    expect(screen.getByRole('tooltip')).toHaveTextContent(/links to the provider page/);
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('tooltip')).toBeNull();
  });

  it('has no second question field: an example question opens the guide on the home page', async () => {
    const m = mockApi({ 'GET /model-families': () => ({ families: FAMILIES }) });
    const { router } = renderApp('/models');
    const examples = await screen.findByRole('list', { name: 'Example questions' });
    expect(screen.getAllByRole('searchbox')).toHaveLength(1);
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    await userEvent.click(within(examples).getByRole('link', { name: 'An open-weight model I can run on my own GPU' }));
    expect(await screen.findByRole('heading', { name: /An open-weight model I can run on my own GPU/ })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({ request: 'An open-weight model I can run on my own GPU' });
  });

  it('offers a comparison question for the first two compared families', async () => {
    mockApi({ 'GET /model-families': () => ({ families: FAMILIES }) });
    renderApp('/models');
    const examples = await screen.findByRole('list', { name: 'Example questions' });
    expect(examples).toHaveTextContent('OpenAI GPT or Anthropic Claude for coding agents?');
  });

  it('adds a family from the picker and reads the selection from the URL', async () => {
    mockApi({ 'GET /model-families': () => ({ families: FAMILIES }) });
    renderApp('/models?compare=meta');
    const table = await screen.findByRole('table');
    expect(within(table).getByRole('row', { name: /Open weights/ })).toHaveTextContent('Yes');
    await userEvent.click(screen.getByRole('checkbox', { name: /OpenAI GPT/ }));
    expect(within(screen.getByRole('table')).getAllByRole('columnheader')).toHaveLength(3);
  });
});
