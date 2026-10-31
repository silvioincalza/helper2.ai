import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { SOURCES, makeStatus } from '@/test/fixtures';
import { PicksBar } from './PicksBar';
import { ProofQuote } from './ProofQuote';
import { SelectableRow } from './SelectableRow';
import { SourceTiles } from './SourceTiles';
import { StatusPill } from './StatusPill';
import { TypeTag } from './TypeTag';

describe('TypeTag', () => {
  it('names the type in words, with the tone of its type group', async () => {
    const { container } = render(<p><TypeTag type="MCP_SERVER" /> <TypeTag type="LIBRARY" /> <TypeTag type="DATASET" /></p>);
    expect(screen.getByText('MCP server')).toHaveClass('h2-type-tag', 'h2-type-tag--mcp');
    // A library shares the paper colours (design system, Type tags).
    expect(screen.getByText('Library')).toHaveClass('h2-type-tag--paper');
    expect(screen.getByText('Dataset')).toHaveClass('h2-type-tag--other');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('names a finer kind when the source states one, in the colours of the type', () => {
    render(<TypeTag type="EVENT">Hackathon</TypeTag>);
    expect(screen.getByText('Hackathon')).toHaveClass('h2-type-tag', 'h2-type-tag--other');
  });
});

describe('StatusPill', () => {
  it('says the status in words; the tone only repeats it', async () => {
    const { container } = render(<p><StatusPill tone="ok">Updated</StatusPill> <StatusPill tone="next">In 13 days</StatusPill></p>);
    expect(screen.getByText('Updated')).toHaveClass('h2-status-pill', 'h2-status-pill--ok');
    expect(screen.getByText('In 13 days')).toHaveClass('h2-status-pill--next');
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('ProofQuote', () => {
  const text = 'A server for agents. It gives read-only access to a PostgreSQL database. MIT licensed.';

  it('shows the source text with the checked sentence highlighted, and names the source', async () => {
    const { container } = render(<ProofQuote text={text} quote="read-only access to a postgresql database" source="GitHub" />);
    expect(container.querySelector('mark')).toHaveTextContent('read-only access to a PostgreSQL database');
    expect(container.querySelector('blockquote')).toHaveTextContent(text);
    expect(screen.getByText('GitHub')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('shows the quote alone, highlighted, when the source text does not hold it', () => {
    const { container } = render(<ProofQuote text="Some other description" quote="Postgres MCP" source="GitHub" />);
    expect(container.querySelector('blockquote')).toHaveTextContent(/^Postgres MCP$/);
    expect(container.querySelector('mark')).toHaveTextContent('Postgres MCP');
  });

  it('is never marked AI-written: it is the source’s own text (ADR 0038)', () => {
    const { container } = render(<ProofQuote text={text} quote="MIT licensed" source="GitHub" />);
    expect(container.querySelector('[data-ai-generated]')).toBeNull();
  });
});

describe('SourceTiles', () => {
  it('gives each source its state in words: time, cache age or what went wrong', async () => {
    const now = Date.parse('2026-10-30T10:00:00Z');
    const { container } = render(
      <SourceTiles now={now} search={[
        makeStatus({ sourceId: 'github', name: 'GitHub', outcome: 'SUCCEEDED', servedFrom: 'LIVE', latencyMs: 320 }),
        makeStatus({ sourceId: 'hf', name: 'Hugging Face', outcome: 'SUCCEEDED', servedFrom: 'CACHE', cachedAt: '2026-10-30T09:56:00Z' }),
        makeStatus({ sourceId: 'npm', name: 'npm', outcome: 'TIMED_OUT', servedFrom: 'NONE', latencyMs: null }),
      ]} />,
    );
    const tiles = screen.getAllByRole('listitem');
    expect(tiles[0]).toHaveTextContent('GitHub');
    expect(tiles[0]).toHaveTextContent('320 ms');
    expect(tiles[0]).toHaveClass('is-ok');
    expect(tiles[1]).toHaveTextContent('cached 4 min. ago');
    expect(tiles[1]).toHaveClass('is-warn');
    expect(tiles[2]).toHaveTextContent('Timed out');
    expect(tiles[2]).toHaveClass('is-failed');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('gives each configured source its last outcome and when, not asked yet, or turned off', async () => {
    const now = Date.parse('2026-10-30T10:00:00Z');
    const { container } = render(
      <SourceTiles now={now} label="Sources" registry={[
        { ...SOURCES[0]!, lastOutcome: 'SUCCEEDED', lastCheckedAt: '2026-10-30T08:00:00Z' },
        { ...SOURCES[0]!, id: 'arxiv', name: 'arXiv', lastOutcome: 'RATE_LIMITED', lastCheckedAt: '2026-10-30T09:55:00Z' },
        { ...SOURCES[1]!, lastOutcome: null, lastCheckedAt: null },
        { ...SOURCES[0]!, id: 'tavily', name: 'Tavily', enabled: false },
      ]} />,
    );
    const tiles = within(screen.getByRole('list', { name: 'Sources' })).getAllByRole('listitem');
    expect(tiles[0]).toHaveTextContent('GitHubSucceeded 2 hr. ago');
    expect(tiles[0]).toHaveClass('is-ok');
    expect(tiles[1]).toHaveTextContent('Rate limited 5 min. ago');
    expect(tiles[1]).toHaveClass('is-warn');
    expect(tiles[2]).toHaveTextContent('Not asked yet');
    expect(tiles[2]).toHaveClass('is-off');
    expect(tiles[3]).toHaveTextContent('Turned off');
    expect(tiles[3]).toHaveClass('is-off');
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('SelectableRow', () => {
  it('is named by the parts the list names and described by the rest, with the tab stop the list gives', () => {
    render(
      <SelectableRow selected onSelect={() => undefined} labelledBy="t m" describedBy="d" tabIndex={-1}>
        <span id="m">MCP server</span> <span id="t">Postgres MCP</span> <span id="d">Read-only SQL.</span>
      </SelectableRow>,
    );
    const button = screen.getByRole('button', { name: 'Postgres MCP MCP server' });
    expect(button).toHaveAccessibleDescription('Read-only SQL.');
    expect(button).toHaveAttribute('tabindex', '-1');
  });

  it('is a real button; the selected row says so to assistive tech and gets the selected outline', async () => {
    const onSelect = vi.fn();
    const { container } = render(
      <ul>
        <li><SelectableRow selected onSelect={onSelect}>Postgres MCP</SelectableRow></li>
        <li><SelectableRow selected={false} onSelect={onSelect}>SQLite MCP</SelectableRow></li>
      </ul>,
    );
    expect(screen.getByRole('button', { name: 'Postgres MCP' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: 'SQLite MCP' })).not.toHaveAttribute('aria-current');
    await userEvent.click(screen.getByRole('button', { name: 'SQLite MCP' }));
    expect(onSelect).toHaveBeenCalledOnce();
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('PicksBar', () => {
  it('waits in a box of reserved height, saying how many candidates the guide reads', async () => {
    const { container } = render(<PicksBar state={{ kind: 'reading', candidates: 24 }} />);
    const bar = screen.getByRole('region', { name: 'Guide picks' });
    expect(bar).toHaveClass('h2-picks', 'is-reading');
    expect(within(bar).getByRole('status')).toHaveTextContent('Guide is reading 24 candidates… picks will appear here');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('before the candidates are counted, says it is reading the results', () => {
    render(<PicksBar state={{ kind: 'reading' }} />);
    expect(screen.getByRole('status')).toHaveTextContent('Guide is reading the results… picks will appear here');
  });

  it('with no pick, shrinks to one line that says why', async () => {
    const { container } = render(<PicksBar state={{ kind: 'none' }} />);
    expect(screen.getByRole('region', { name: 'Guide picks' })).toHaveClass('is-none');
    expect(screen.getByRole('status')).toHaveTextContent('No pick: no result had a sentence that proves it');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('shows the picks under an AI-written label; their titles come from the sources, so they are not marked (ADR 0038)', async () => {
    const { container } = render(
      <PicksBar state={{ kind: 'picks' }}>
        <ol><li>#1 Postgres MCP</li></ol>
      </PicksBar>,
    );
    const bar = screen.getByRole('region', { name: 'Guide picks' });
    expect(within(bar).getByText('AI-written')).toBeInTheDocument();
    expect(within(bar).getByText('#1 Postgres MCP').closest('[data-ai-generated]')).toBeNull();
    expect(within(bar).queryByRole('status')).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('names the question in its head and closes with a labelled button', async () => {
    const onClose = vi.fn();
    render(<PicksBar state={{ kind: 'reading' }} question="an MCP server for Postgres" onClose={onClose} closeLabel="Close the guide answer" />);
    expect(screen.getByText('an MCP server for Postgres').tagName).toBe('Q');
    await userEvent.click(screen.getByRole('button', { name: 'Close the guide answer' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('with no pick for another reason, says that reason on its one line', () => {
    render(<PicksBar state={{ kind: 'none', reason: 'The AI model is not available right now.' }} />);
    expect(screen.getByRole('status')).toHaveTextContent('The AI model is not available right now.');
  });

  it('a question back sits under the AI-written label, the answer\'s provider and models on the bar', () => {
    render(<PicksBar state={{ kind: 'ask' }} ai={{ provider: 'nebius', models: ['nano'] }}><h3>New tools for what?</h3></PicksBar>);
    const bar = screen.getByRole('region', { name: 'Guide picks' });
    expect(within(bar).getByText('AI-written')).toBeInTheDocument();
    expect(bar).toHaveAttribute('data-ai-provider', 'nebius');
    expect(bar).toHaveAttribute('data-ai-models', 'nano');
    expect(within(bar).queryByRole('status')).toBeNull();
  });

  it('an error shows what the page passes, with no AI-written label', () => {
    render(<PicksBar state={{ kind: 'error' }}><p role="alert">The model timed out.</p></PicksBar>);
    expect(screen.getByRole('alert')).toHaveTextContent('The model timed out.');
    expect(screen.queryByText('AI-written')).toBeNull();
  });

  it('shows its footer in every state', () => {
    render(<PicksBar state={{ kind: 'reading' }} footer={<p>Understood as: New resources</p>} />);
    expect(screen.getByText('Understood as: New resources')).toBeInTheDocument();
  });
});

