import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { SOURCES } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';

describe('Feature: the sources Helper2.ai searches', () => {
  it('Scenario: a visitor opens Sources from the footer and sees how each source answered last', async () => {
    // Given the registry with one source that answered and one that was never asked
    mockApi();
    const { router } = renderApp('/');
    // When they follow Sources in the footer
    await userEvent.click(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Sources' }));
    // Then the page says it searches these sources and nothing else
    expect(router.state.location.pathname).toBe('/sources');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sources we search' })).toHaveFocus();
    // And one tile per source states its last outcome in words, or that it was not asked yet
    const tiles = within(await screen.findByRole('list', { name: 'Last outcome' })).getAllByRole('listitem');
    expect(tiles).toHaveLength(2);
    expect(tiles[0]).toHaveTextContent(/GitHubSucceeded .+ ago/);
    expect(tiles[1]).toHaveTextContent('Helper2 seedNot asked yet');
  });

  it('Scenario: each source names its mode, what it lists, its terms and its attribution', async () => {
    mockApi();
    const { container } = renderApp('/sources');
    const table = await screen.findByRole('table', { name: 'What each source covers' });
    const gh = within(table).getByRole('row', { name: /GitHub/ });
    expect(within(gh).getByRole('link', { name: /GitHub/ })).toHaveAttribute('href', 'https://github.com/');
    expect(within(gh).getByText('Live')).toBeInTheDocument();
    expect(within(gh).getByText('Library, MCP server')).toBeInTheDocument();
    expect(within(gh).getByRole('link', { name: /Terms/ })).toHaveAttribute('rel', 'noopener noreferrer nofollow');
    expect(within(gh).getByText('Data from GitHub')).toBeInTheDocument();
    const seed = within(table).getByRole('row', { name: /Helper2 seed/ });
    expect(within(seed).getByText('Curated')).toBeInTheDocument();
    expect(within(seed).getAllByText('Unknown')).toHaveLength(1);
    // And the modes are explained once, above the table
    expect(screen.getByRole('heading', { name: 'How sources work' })).toBeInTheDocument();
    expect(screen.getByText('Queried at the moment you search.')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: a source turned off says so', async () => {
    mockApi({ 'GET /sources': () => [{ ...SOURCES[0]!, enabled: false }] });
    renderApp('/sources');
    expect(await within(await screen.findByRole('list', { name: 'Last outcome' })).findByText('Turned off')).toBeInTheDocument();
  });

  it('Scenario: while the list loads, the page says so', async () => {
    mockApi({ 'GET /sources': () => new Promise(() => undefined) });
    renderApp('/sources');
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('Scenario: the list cannot load; Try again keeps focus on the page and shows the sources', async () => {
    // Given the registry fails once
    let fail = true;
    const m = mockApi({ 'GET /sources': () => (fail ? problem(503, 'Service Unavailable') : SOURCES) });
    const { container } = renderApp('/sources');
    // Then an alert says what happened and offers Try again
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('heading', { name: 'Could not load the sources' })).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    // When the user tries again by keyboard
    fail = false;
    within(alert).getByRole('button', { name: 'Try again' }).focus();
    await userEvent.keyboard('{Enter}');
    // Then the sources load and focus stays on the page heading
    expect(await screen.findByRole('table')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toHaveFocus());
    expect(m.callsTo('GET', '/sources')).toHaveLength(2);
  });

  it('Scenario: no source is configured', async () => {
    mockApi({ 'GET /sources': () => [] });
    renderApp('/sources');
    expect(await screen.findByRole('heading', { name: 'No sources are configured' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the home page' })).toHaveAttribute('href', '/');
  });
});
