import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { mockApi } from '@/test/mockApi';
import { renderApp, settled } from '@/test/render';

describe('mission and privacy pages', () => {
  it('footer links open the mission and privacy pages inside the app', async () => {
    mockApi();
    const { router } = renderApp('/');
    const footer = screen.getByRole('contentinfo');

    await userEvent.click(within(footer).getByRole('link', { name: 'Mission' }));
    expect(router.state.location.pathname).toBe('/mission');
    expect(screen.getByRole('heading', { level: 1, name: 'Our mission' })).toBeInTheDocument();

    await userEvent.click(within(footer).getByRole('link', { name: 'Privacy' }));
    expect(router.state.location.pathname).toBe('/privacy');
    expect(screen.getByRole('heading', { level: 1, name: 'Privacy' })).toBeInTheDocument();
  });

  it('mission page states the promises and links to GitHub issues in a new tab', async () => {
    mockApi();
    renderApp('/mission');
    await settled();
    expect(document.title).toBe('Mission · Helper2.ai');
    expect(screen.getByRole('region', { name: 'What we promise' })).toHaveTextContent('Proof before opinion');
    expect(screen.getByRole('region', { name: 'How the guide answers' })).toHaveTextContent('A validator checks');
    expect(screen.getByRole('region', { name: 'Never guessed' })).toBeInTheDocument();
    const issues = screen.getByRole('link', { name: 'Open an issue on GitHub (opens in a new tab)' });
    expect(issues).toHaveAttribute('href', 'https://github.com/silvioincalza/helper2.ai/issues');
    expect(issues).toHaveAttribute('target', '_blank');
    expect(issues).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('privacy page names the contact, the cookies and the data recipients', async () => {
    mockApi();
    renderApp('/privacy');
    await settled();
    expect(document.title).toBe('Privacy · Helper2.ai');
    expect(screen.getByRole('link', { name: 'helper2ai@incalza.me' })).toHaveAttribute('href', 'mailto:helper2ai@incalza.me');
    expect(screen.getByRole('region', { name: 'Cookies and browser storage' })).toHaveTextContent('XSRF-TOKEN');
    const recipients = screen.getByRole('region', { name: 'Who receives data' });
    expect(recipients).toHaveTextContent('Nebius Token Factory');
    expect(recipients).toHaveTextContent('Germany');
    expect(screen.getByRole('region', { name: 'Your rights' })).toHaveTextContent('FADP');
  });

  it.each(['/mission', '/privacy'])('%s has no axe violations', async (path) => {
    mockApi();
    const { container } = renderApp(path);
    await screen.findByRole('heading', { level: 1 });
    expect(await axeViolations(container)).toEqual([]);
  });
});
