import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { makeCard, makeSearch } from '@/test/fixtures';
import type { SetupGuide, ToolGuide } from '@/api/types';

const A = '11111111-1111-5111-8111-111111111111';
const B = '22222222-2222-5222-8222-222222222222';
const P = '33333333-3333-5333-8333-333333333333';

const setup = (id: string, title: string): SetupGuide => ({ resourceId: id, title, version: null, options: [], clients: [], sources: [] });
const steps = (id: string, title: string): ToolGuide => ({
  resourceId: id, title, status: 'OK', rejected: 0, generatedAt: '2026-10-28T00:00:00Z', model: 'nano',
  sections: [{ kind: 'INSTALL', steps: [{ text: `Install ${title}.`, quote: 'npm install', source: 'readme', caution: false, code: { language: 'bash', text: `npm install ${title}` } }] }],
  documents: [{ key: 'readme', kind: 'README', url: 'https://github.com/acme/x', retrievedAt: '2026-10-28T00:00:00Z' }],
});

function mock() {
  return mockApi({
    'GET /search': () => makeSearch({
      results: [
        makeCard({ id: A, title: 'alpha-mcp', type: 'MCP_SERVER' }),
        makeCard({ id: B, title: 'beta-lib', type: 'LIBRARY' }),
        makeCard({ id: P, title: 'A survey of agents', type: 'PAPER' }),
      ],
      total: 3,
    }),
    [`GET /resources/${A}/setup`]: () => setup(A, 'alpha-mcp'),
    [`GET /resources/${B}/setup`]: () => setup(B, 'beta-lib'),
    [`POST /resources/${A}/guide`]: () => steps(A, 'alpha-mcp'),
    [`POST /resources/${B}/guide`]: () => steps(B, 'beta-lib'),
  });
}

describe('Guide me in the result preview', () => {
  it('the preview of the selected result opens its setup guide, focus on its heading; papers get none', async () => {
    const m = mock();
    renderApp('/search?q=agents');
    const preview = () => screen.getByRole('article', { name: /alpha-mcp|beta-lib|A survey of agents/ });
    const alpha = await screen.findByRole('button', { name: 'Guide me: alpha-mcp' });
    expect(m.callsTo('POST', `/resources/${A}/guide`)).toHaveLength(0);

    await userEvent.click(alpha);
    expect(alpha).toHaveAttribute('aria-expanded', 'true');
    const heading = await within(preview()).findByRole('heading', { name: 'Set up alpha-mcp' });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(await screen.findByText('npm install alpha-mcp')).toBeInTheDocument();

    await userEvent.click(within(screen.getByRole('list', { name: 'Search results' })).getByText('beta-lib'));
    expect(await screen.findByRole('button', { name: 'Guide me: beta-lib' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Set up alpha-mcp' })).not.toBeInTheDocument();

    await userEvent.click(within(screen.getByRole('list', { name: 'Search results' })).getByText('A survey of agents'));
    await waitFor(() => expect(preview()).toHaveTextContent('A survey of agents'));
    expect(screen.queryByRole('button', { name: /^Guide me:/ })).not.toBeInTheDocument();
  });
});
