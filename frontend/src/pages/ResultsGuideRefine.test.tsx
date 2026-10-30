import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse } from '@/api/types';
import i18n from '@/i18n';
import { mockApi, problem, sse } from '@/test/mockApi';
import { renderApp } from '@/test/render';

const OK: GuideResponse = {
  status: 'OK',
  searchQuery: 'new mcp servers',
  recommendations: [],
  candidatesConsidered: 0,
  sources: [],
  notices: [],
  generatedAt: '2026-10-30T10:00:00Z',
  constraints: ['free'],
};
const ASK_BACK: GuideResponse = {
  ...OK,
  status: 'NEEDS_CLARIFICATION',
  clarification: { question: 'New tools for what?', options: ['coding', 'agents'] },
};

const guideCalls = (m: ReturnType<typeof mockApi>) => m.callsTo('POST', '/guide/stream').map((c) => c.body);

describe('refining a guide question', () => {
  it('given the guide asks back, when the user picks an option, then it asks again with the reply and the URL keeps it', async () => {
    const m = mockApi({ 'POST /guide/stream': ({ body }) => sse((body as { clarification?: string }).clarification ? OK : ASK_BACK) });
    const { params } = renderApp('/search?q=new+tools&ask=new+tools');

    await userEvent.click(await screen.findByRole('button', { name: 'agents' }));

    expect(await screen.findByRole('button', { name: 'Remove “free” and ask again' })).toBeInTheDocument();
    expect(guideCalls(m)).toEqual([{ request: 'new tools' }, { request: 'new tools', clarification: 'agents' }]);
    expect(params().get('clar')).toBe('agents');
  });

  it('given a refined question failed, when the user retries, then the retry sends the same refinement', async () => {
    let n = 0;
    const m = mockApi({ 'POST /guide/stream': () => (n++ === 0 ? problem(502, 'Bad gateway', 'The model timed out.') : sse(OK)) });
    renderApp('/search?q=new+tools&ask=new+tools&clar=agents&drop=window');

    expect(await screen.findByRole('alert')).toHaveTextContent('The model timed out.');
    await userEvent.click(screen.getByRole('button', { name: /Try again/ }));

    expect(await screen.findByRole('button', { name: 'Remove “free” and ask again' })).toBeInTheDocument();
    const refined = { request: 'new tools', clarification: 'agents', drop: ['window'] };
    expect(guideCalls(m)).toEqual([refined, refined]);
  });

  it('given removed parts, when the user removes another, then both are sent and both stay in the URL', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(OK) });
    const { params } = renderApp('/search?q=new+tools&ask=new+tools&drop=window');

    await userEvent.click(await screen.findByRole('button', { name: 'Remove “free” and ask again' }));

    await vi.waitFor(() => expect(guideCalls(m)).toHaveLength(2));
    expect(guideCalls(m)[1]).toEqual({ request: 'new tools', drop: ['window', 'constraint:free'] });
    expect(params().getAll('drop')).toEqual(['window', 'constraint:free']);
  });

  it('given a refined answer, when the user asks a new question, then it is asked without the old refinement', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(OK) });
    const { params } = renderApp('/search?q=new+tools&ask=new+tools&clar=agents');
    await screen.findByRole('button', { name: 'Remove “free” and ask again' });

    await userEvent.clear(screen.getByLabelText('Search AI resources'));
    await userEvent.type(screen.getByLabelText('Search AI resources'), 'speech to text models');
    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));

    await vi.waitFor(() => expect(guideCalls(m)).toHaveLength(2));
    expect(guideCalls(m)[1]).toEqual({ request: 'speech to text models' });
    expect(params().has('clar')).toBe(false);
  });

  it('given an answer on screen, when the language changes, then the guide is not asked again', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(OK) });
    renderApp('/search?q=new+tools&ask=new+tools');
    await screen.findByRole('button', { name: 'Remove “free” and ask again' });

    await act(async () => { await i18n.changeLanguage('de'); });
    await act(async () => { await i18n.changeLanguage('en'); });

    expect(guideCalls(m)).toHaveLength(1);
  });
});
