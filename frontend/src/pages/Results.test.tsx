import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse, ResultCard } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeSearch, makeStatus } from '@/test/fixtures';
import { mockApi, problem, sse } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';


const A = makeCard({ id: 'aaaaaaaa-aaaa-5aaa-8aaa-aaaaaaaaaaaa', title: 'Alpha Search', url: 'https://www.alpha.example/x', license: null });
const B = makeCard({
  id: 'bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb', title: 'Postgres MCP', type: 'MCP_SERVER', url: 'https://github.com/acme/postgres-mcp',
  description: 'An MCP server that gives agents read-only access to a PostgreSQL database. Easy to set up.',
});
const results: ResultCard[] = [A, B];
const search = () => makeSearch({ results, total: 2, sources: [makeStatus(), makeStatus({ sourceId: 'npm', name: 'npm', servedFrom: 'CACHE', cachedAt: '2026-10-22T03:00:00Z' })] });

function guide(): GuideResponse {
  return {
    status: 'OK', searchQuery: 'postgres mcp', candidatesConsidered: 2, rejected: 0, sources: [], notices: [], generatedAt: '2026-10-25T10:00:00Z',
    prompt: null, model: { provider: 'nebius-token-factory', fastModel: 'f', reasoningModel: 'nvidia/nemotron-3-super' },
    recommendations: [{
      resourceId: B.id, title: B.title, url: B.url, type: 'MCP_SERVER', reason: 'Read-only access fits an agent that only reads.',
      quote: 'gives agents read-only access to a PostgreSQL database', sourceIds: ['github'], aiReasoned: true, reasonCheck: 'NOT_CHECKED',
    }],
  };
}

const row = (name: string) => screen.getAllByRole('button', { name: new RegExp(name) }).find((b) => b.classList.contains('h2-select-row'))!;
const rowTitles = () => [...document.querySelectorAll('.h2-select-row .h2-row__title')].map((e) => e.textContent);

describe('results: list and preview', () => {
  it('selects the first result and shows its preview; a click selects another and writes its id to the URL', async () => {
    mockApi({ 'GET /search': () => search() });
    const { params } = renderApp('/search?q=postgres');
    const preview = await screen.findByRole('article');
    expect(within(preview).getByRole('heading', { level: 2, name: 'Alpha Search' })).toBeInTheDocument();
    expect(within(preview).getByText('Not stated')).toBeInTheDocument();
    await userEvent.click(row('Postgres MCP'));
    await waitFor(() => expect(params().get('id')).toBe(B.id));
    expect(within(screen.getByRole('article')).getByRole('heading', { level: 2, name: 'Postgres MCP' })).toBeInTheDocument();
    expect(row('Postgres MCP')).toHaveAttribute('aria-current', 'true');
  });

  it('opens with the result named by id in the URL, and an unknown id selects the first', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp(`/search?q=postgres&id=${B.id}`);
    expect(within(await screen.findByRole('article')).getByRole('heading', { level: 2 })).toHaveTextContent('Postgres MCP');
  });

  it('an unknown id selects the first result', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres&id=not-here');
    expect(within(await screen.findByRole('article')).getByRole('heading', { level: 2 })).toHaveTextContent('Alpha Search');
  });

  it('the guide answer arrives later: the bar fills, the pick gets its badge and reason, and the rows do not move', async () => {
    let answer: (r: Response) => void = () => undefined;
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => new Promise<Response>((r) => { answer = r; }) });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    expect(await screen.findByText('Guide is reading the results… picks will appear here')).toBeInTheDocument();
    await screen.findByRole('article');
    const before = rowTitles();
    // jsdom cannot measure: the slot that keeps the row of "How the guide decided" stands in for it while the guide reads.
    expect(document.querySelector('.h2-results__full-slot')).toBeInTheDocument();
    expect(screen.queryByText('How the guide decided')).not.toBeInTheDocument();
    await act(async () => { answer(sse(guide())); });
    const chip = await screen.findByRole('button', { name: '#1 Postgres MCP' });
    expect(rowTitles()).toEqual(before);
    expect(screen.getByText('How the guide decided')).toBeInTheDocument();
    expect(document.querySelector('.h2-results__full-slot')).not.toBeInTheDocument();
    expect(screen.getByText('Pick #1')).toBeInTheDocument();
    const reason = screen.getAllByText('Read-only access fits an agent that only reads.')[0]!;
    expect(reason).toHaveAttribute('data-ai-generated', 'true');
    await userEvent.click(chip);
    const preview = screen.getByRole('article');
    expect(within(preview).getByRole('heading', { level: 2 })).toHaveTextContent('Postgres MCP');
    expect(within(preview).getByText('gives agents read-only access to a PostgreSQL database').tagName).toBe('MARK');
  });

  it('an AI-written description in a row is labelled and marked machine-readable', async () => {
    const ai = makeCard({ id: 'dddddddd-dddd-5ddd-8ddd-dddddddddddd', title: 'Summed Up', description: 'A short summary.', descriptionOrigin: 'AI_SUMMARY' });
    mockApi({ 'GET /search': () => makeSearch({ results: [A, ai], total: 2, sources: [makeStatus()] }) });
    renderApp('/search?q=postgres+mcp');
    await screen.findByText('Summed Up');
    const r = row('Summed Up');
    expect(within(r).getByText('AI summary')).toBeInTheDocument();
    expect(within(r).getByText('A short summary.')).toHaveAttribute('data-ai-generated', 'true');
  });

  it('a fact the sources disagree on says so, with each value and its source, not "Not stated"', async () => {
    const split = makeCard({
      id: 'eeeeeeee-eeee-5eee-8eee-eeeeeeeeeeee', title: 'Split License', license: null,
      conflicts: [{ field: 'license', values: [{ value: 'MIT', sourceId: 'github' }, { value: 'Apache-2.0', sourceId: 'npm' }] }],
    });
    mockApi({ 'GET /search': () => makeSearch({ results: [split], total: 1, sources: [makeStatus()] }) });
    renderApp('/search?q=postgres+mcp');
    const preview = await screen.findByRole('article');
    expect(preview.querySelector('dd.is-conflict')).toHaveTextContent('Sources disagree: MIT (github) · Apache-2.0 (npm)');
    expect(preview.querySelector('dd.is-conflict')).not.toHaveTextContent('Not stated');
  });

  it('with no pick the bar says so in one line', async () => {
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse({ ...guide(), recommendations: [] }) });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    expect(await screen.findByText('No pick: no result had a sentence that proves it')).toBeInTheDocument();
  });

  it('an answer with no AI pick but plain recommendations is shown in full, not as "no pick"', async () => {
    const plain = { ...guide().recommendations[0]!, aiReasoned: false };
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse({ ...guide(), recommendations: [plain] }) });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    expect(await screen.findByRole('link', { name: /Postgres MCP/, hidden: false })).toBeInTheDocument();
    expect(screen.queryByText('No pick: no result had a sentence that proves it')).not.toBeInTheDocument();
  });

  it('a pick that is not on this page of results links to its own page', async () => {
    const away = { ...guide().recommendations[0]!, resourceId: 'cccccccc-cccc-5ccc-8ccc-cccccccccccc', title: 'Elsewhere MCP' };
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse({ ...guide(), recommendations: [away] }) });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    const chip = await screen.findByRole('link', { name: /#1\s*Elsewhere MCP/ });
    expect(chip).toHaveAttribute('href', '/resource/cccccccc-cccc-5ccc-8ccc-cccccccccccc');
  });

  it('the timeline view pages too', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results, total: 120, size: 50, sources: [makeStatus()] }) });
    renderApp('/search?q=postgres+mcp&view=timeline');
    expect(await screen.findByRole('button', { name: /Next/ })).toBeInTheDocument();
  });

  it('up to 860 px the preview opens inside the list, under the selected row, with a link to the full page', async () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener: () => undefined, removeEventListener: () => undefined })));
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres');
    const full = await screen.findByRole('link', { name: 'Full details' });
    expect(full).toHaveAttribute('href', `/resource/${A.id}`);
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(full.closest('li')).toContainElement(row('Alpha Search'));
    vi.unstubAllGlobals();
  });

  it('shows the sources that answered at the bottom', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres');
    expect(await screen.findByRole('heading', { name: /2 of 2 sources answered/ })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Sources searched' })).toBeInTheDocument();
  });

  it('Try again on a failed guide keeps the reader there: focus moves to the picks heading, not the page body', async () => {
    let n = 0;
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => (n++ === 0 ? problem(502, 'Bad gateway', 'The model timed out.') : sse(guide())) });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    expect(await screen.findByRole('alert')).toHaveTextContent('The model timed out.');
    screen.getByRole('button', { name: /Try again/ }).focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByRole('button', { name: '#1 Postgres MCP' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Guide picks' })).toHaveFocus();
  });

  it('has one h1 and no axe violations', async () => {
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse(guide()) });
    const { container } = renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    await settle();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(await axeViolations(container)).toEqual([]);
  });
});
