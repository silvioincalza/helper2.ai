import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { makeStatus } from '@/test/fixtures';
import { CopyButton } from './CopyButton';
import { PicksBar } from './PicksBar';
import { RetryButton, StatePanel } from './StatePanel';
import { ProofQuote } from './ProofQuote';
import { SelectableRow } from './SelectableRow';
import { SourceTiles } from './SourceTiles';
import { StatusPill } from './StatusPill';
import { TypeTag } from './TypeTag';

describe('TypeTag', () => {
  it('names the type in words, with the tone of its type group', async () => {
    const { container } = render(<p><TypeTag type="MCP_SERVER" /> <TypeTag type="LIBRARY" /> <TypeTag type="DATASET" /></p>);
    expect(screen.getByText('MCP server')).toHaveClass('h2-type-tag', 'h2-type-tag--mcp');
    // A library shares the paper colours (design system v3).
    expect(screen.getByText('Library')).toHaveClass('h2-type-tag--paper');
    expect(screen.getByText('Dataset')).toHaveClass('h2-type-tag--other');
    expect(await axeViolations(container)).toEqual([]);
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
      <SourceTiles now={now} sources={[
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
});

describe('SelectableRow', () => {
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

  it('shows the picks under an AI-written label, marked machine-readable (ADR 0038)', async () => {
    const { container } = render(
      <PicksBar state={{ kind: 'picks', model: 'nvidia/nemotron-3-super' }}>
        <ol><li>#1 Postgres MCP</li></ol>
      </PicksBar>,
    );
    const bar = screen.getByRole('region', { name: 'Guide picks' });
    expect(within(bar).getByText('AI-written')).toBeInTheDocument();
    const picks = within(bar).getByText('#1 Postgres MCP').closest('[data-ai-generated]');
    expect(picks).toHaveAttribute('data-ai-model', 'nvidia/nemotron-3-super');
    expect(within(bar).queryByRole('status')).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('CopyButton', () => {
  it('copies its text and says so on the button', async () => {
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    render(<CopyButton text="npx server" label="Copy prompt" />);
    await user.click(screen.getByRole('button', { name: 'Copy prompt' }));
    expect(writeText).toHaveBeenCalledWith('npx server');
    expect(screen.getByRole('button', { name: 'Copied' })).toBeInTheDocument();
  });

  it('is named Copy when the snippet needs no other name', () => {
    render(<CopyButton text="x" />);
    expect(screen.getByRole('button', { name: 'Copy' })).toBeInTheDocument();
  });
});

describe('StatePanel', () => {
  it('an error is an alert: its heading says what happened, the next step sits under it', async () => {
    const retry = vi.fn();
    const { container } = render(<StatePanel tone="error" level={2} title="Could not load" next={<RetryButton onClick={retry} />}><p>The server did not answer.</p></StatePanel>);
    const alert = screen.getByRole('alert');
    expect(within(alert).getByRole('heading', { level: 2, name: 'Could not load' })).toBeInTheDocument();
    expect(alert).toHaveTextContent('The server did not answer.');
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect(retry).toHaveBeenCalledTimes(1);
    expect(await axeViolations(container)).toEqual([]);
  });

  it('a notice is read with the page, or announced as a status when it is live; it may have no next step', () => {
    const { rerender } = render(<StatePanel tone="notice" level={3} title="Nothing new this week" />);
    expect(screen.getByRole('heading', { level: 3, name: 'Nothing new this week' })).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    rerender(<StatePanel tone="notice" live level={2} title="No results" />);
    expect(screen.getByRole('status')).toHaveTextContent('No results');
  });
});
