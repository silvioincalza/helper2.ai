import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { makeSearch, makeStatus } from '@/test/fixtures';
import { PicksBar } from '@/components/ui/PicksBar';
import { ResultKeys, stepRow } from './ResultKeys';
import { SourceLine } from './SourceLine';

describe('SourceLine', () => {
  it('says how many sources answered, how many live with the slowest, and how many from the cache', async () => {
    const res = makeSearch({
      sources: [
        makeStatus({ sourceId: 'a', latencyMs: 177 }),
        makeStatus({ sourceId: 'b', latencyMs: 16 }),
        makeStatus({ sourceId: 'c', servedFrom: 'CACHE', cachedAt: '2026-10-22T08:58:00Z' }),
      ],
    });
    const { container } = render(<SourceLine res={res} />);
    const line = container.querySelector('.h2-source-line');
    expect(line).toHaveTextContent(
      '3 of 3 sources answered · 2 live, slowest 177 ms · 1 from the cache · Details',
    );
    expect(screen.getByRole('link', { name: 'Details' })).toHaveAttribute('href', '#h2-sources');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('is not there when some sources did not answer: the coverage line says it', () => {
    const res = makeSearch({
      sources: [makeStatus({ sourceId: 'a' }), makeStatus({ sourceId: 'b', outcome: 'FAILED' })],
    });
    res.coverage.partial = true;
    const { container } = render(<SourceLine res={res} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('PicksBar as a terminal', () => {
  it('shows the command with the pick count, hidden from screen readers, and the model that chose', () => {
    render(
      <PicksBar state={{ kind: 'picks', model: 'nvidia/nemotron-3-super', count: 3 }}>
        <ol>
          <li>#1 Postgres MCP</li>
        </ol>
      </PicksBar>,
    );
    const bar = screen.getByRole('region', { name: 'Guide picks' });
    const cmd = bar.querySelector('.h2-picks__cmd');
    expect(cmd).toHaveTextContent('$ helper2 guide --picks 3');
    expect(cmd).toHaveAttribute('aria-hidden', 'true');
    expect(within(bar).getByText('Chosen by nvidia/nemotron-3-super')).toBeInTheDocument();
  });

  it('while reading, shows the command without a count', () => {
    render(<PicksBar state={{ kind: 'reading', candidates: 24 }} />);
    const cmd = screen.getByRole('region', { name: 'Guide picks' }).querySelector('.h2-picks__cmd');
    expect(cmd).toHaveTextContent('$ helper2 guide');
    expect(cmd).not.toHaveTextContent('--picks');
  });
});

describe('stepRow', () => {
  const list = () => {
    document.body.innerHTML = [1, 2, 3]
      .map(
        (n) =>
          `<ul><li class="h2-row"><a class="h2-row__title" href="#${n}">R${n}</a> <span>x</span></li></ul>`,
      )
      .join('');
    return [...document.querySelectorAll<HTMLElement>('.h2-row__title')];
  };

  it('goes to the first row with j and the last with k when the focus is not on a row', () => {
    const links = list();
    expect(stepRow(links, document.body, 1)).toBe(links[0]);
    expect(stepRow(links, document.body, -1)).toBe(links[2]);
  });

  it('moves one row from anything inside a row, and stays at either end', () => {
    const links = list();
    const inRow2 = links[1]!.parentElement!.querySelector('span');
    expect(stepRow(links, inRow2, 1)).toBe(links[2]);
    expect(stepRow(links, links[0]!, -1)).toBe(links[0]);
    expect(stepRow(links, links[2]!, 1)).toBe(links[2]);
  });

  it('finds nothing in an empty list', () => {
    expect(stepRow([], null, 1)).toBeUndefined();
  });
});

describe('ResultKeys', () => {
  const page = () =>
    render(
      <>
        <textarea id="q" aria-label="Search" defaultValue="vector database" />
        <button type="button" className="one-box-ask">
          Ask the guide
        </button>
        <ResultKeys>
          <ul>
            <li className="h2-row">
              <a className="h2-row__title" href="#a">
                Alpha
              </a>
            </li>
            <li className="h2-row">
              <a className="h2-row__title" href="#b">
                Beta
              </a>
            </li>
          </ul>
          <label>
            Sort{' '}
            <select defaultValue="r">
              <option value="r">Relevance</option>
            </select>
          </label>
        </ResultKeys>
      </>,
    );

  it('j and k move between results while the focus is in the results', async () => {
    const user = userEvent.setup();
    page();
    screen.getByRole('link', { name: 'Alpha' }).focus();
    await user.keyboard('j');
    expect(screen.getByRole('link', { name: 'Beta' })).toHaveFocus();
    await user.keyboard('k');
    expect(screen.getByRole('link', { name: 'Alpha' })).toHaveFocus();
  });

  it('/ goes to the box, g asks the guide, ? opens the list of keys', async () => {
    const user = userEvent.setup();
    const ask = vi.fn();
    page();
    screen.getByRole('button', { name: 'Ask the guide' }).addEventListener('click', ask);
    const help = document.querySelector('details.h2-keys') as HTMLDetailsElement;
    screen.getByRole('link', { name: 'Alpha' }).focus();
    await user.keyboard('?');
    expect(help.open).toBe(true);
    await user.keyboard('g');
    expect(ask).toHaveBeenCalledTimes(1);
    screen.getByRole('link', { name: 'Alpha' }).focus();
    await user.keyboard('/');
    expect(screen.getByRole('textbox', { name: 'Search' })).toHaveFocus();
  });

  it('does nothing outside the results, in a field, or with a modifier key', async () => {
    const user = userEvent.setup();
    page();
    screen.getByRole('textbox', { name: 'Search' }).focus();
    await user.keyboard('j');
    expect(screen.getByRole('textbox', { name: 'Search' })).toHaveFocus();
    screen.getByRole('combobox', { name: 'Sort' }).focus();
    await user.keyboard('j');
    expect(screen.getByRole('combobox', { name: 'Sort' })).toHaveFocus();
    screen.getByRole('link', { name: 'Alpha' }).focus();
    await user.keyboard('{Control>}j{/Control}');
    expect(screen.getByRole('link', { name: 'Alpha' })).toHaveFocus();
  });

  it('lists the keys with no accessibility problem', async () => {
    const { container } = page();
    expect(screen.getByText('Keyboard keys')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });
});
