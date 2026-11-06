import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

describe('mission, privacy and terms pages', () => {
  it('footer links open the mission, privacy and terms pages inside the app', async () => {
    mockApi();
    const { router } = renderApp('/');
    const footer = screen.getByRole('contentinfo');

    await userEvent.click(within(footer).getByRole('link', { name: 'Mission' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/mission'));
    // The text pages load on demand.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Our mission' }),
    ).toBeInTheDocument();

    await userEvent.click(within(footer).getByRole('link', { name: 'Privacy' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/privacy'));
    expect(await screen.findByRole('heading', { level: 1, name: 'Privacy' })).toBeInTheDocument();

    await userEvent.click(within(footer).getByRole('link', { name: 'Terms' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/terms'));
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Terms of use' }),
    ).toBeInTheDocument();
  });

  it('mission page states the promises and links to GitHub issues in a new tab', async () => {
    mockApi();
    renderApp('/mission');
    await screen.findByRole('heading', { level: 1, name: 'Our mission' });
    await settle();
    await waitFor(() => expect(document.title).toBe('Mission · Helper2.ai'));
    expect(screen.getByRole('region', { name: 'What we promise' })).toHaveTextContent(
      'Proof before opinion',
    );
    expect(screen.getByRole('region', { name: 'How the guide answers' })).toHaveTextContent(
      'A validator checks',
    );
    expect(screen.getByRole('region', { name: 'Never guessed' })).toBeInTheDocument();
    const marking = screen.getByRole('region', { name: 'How we mark AI text' });
    expect(marking).toHaveTextContent('data-ai-generated');
    expect(marking).toHaveTextContent('“AI-written” in the guide’s picks bar, on its search terms');
    expect(marking).toHaveTextContent(
      '“Read by AI” on the requirements it read from your question',
    );
    expect(marking).toHaveTextContent('“Steps written by AI” in the setup guide');
    expect(marking).toHaveTextContent('a small “AI” mark: “Drafted with AI, checked by a person”');
    const issues = screen.getByRole('link', {
      name: 'Open an issue on GitHub (opens in a new tab)',
    });
    expect(issues).toHaveAttribute('href', 'https://github.com/silvioincalza/helper2.ai/issues');
    expect(issues).toHaveAttribute('target', '_blank');
    expect(issues).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('privacy page names the contact, the cookies and the data recipients', async () => {
    mockApi();
    renderApp('/privacy');
    await screen.findByRole('heading', { level: 1, name: 'Privacy' });
    await settle();
    await waitFor(() => expect(document.title).toBe('Privacy · Helper2.ai'));
    expect(screen.getByRole('link', { name: 'helper2ai@incalza.me' })).toHaveAttribute(
      'href',
      'mailto:helper2ai@incalza.me',
    );
    expect(screen.getByRole('region', { name: 'Cookies and browser storage' })).toHaveTextContent(
      'XSRF-TOKEN',
    );
    const recipients = screen.getByRole('region', { name: 'Who receives data' });
    expect(recipients).toHaveTextContent('Nebius Token Factory');
    expect(recipients).toHaveTextContent('Germany');
    expect(screen.getByRole('region', { name: 'Your rights' })).toHaveTextContent('FADP');
    expect(screen.getByRole('region', { name: 'Legal basis' })).toHaveTextContent(
      'Art. 6(1)(f) GDPR',
    );
    expect(recipients).toHaveTextContent('Data Privacy Framework');
    expect(recipients).toHaveTextContent('Cloudflare (United States)');
    expect(recipients).toHaveTextContent('Hugging Face, npm, arXiv and Tavily are not certified');
    expect(screen.getByRole('region', { name: 'How long we keep data' })).toHaveTextContent(
      'Technical logs and request traces: up to 3 months.',
    );
  });

  it('privacy page states the account data, how long it is kept and how to delete the account', async () => {
    mockApi();
    renderApp('/privacy');
    await screen.findByRole('heading', { level: 1, name: 'Privacy' });
    await settle();
    expect(screen.getByRole('region', { name: 'What we process and why' })).toHaveTextContent(
      'when you last signed in',
    );
    const keep = screen.getByRole('region', { name: 'How long we keep data' });
    expect(keep).toHaveTextContent('24 months after you last signed in');
    expect(keep).toHaveTextContent('Reports: 12 months after they were handled.');
    expect(screen.getByRole('region', { name: 'What we process and why' })).toHaveTextContent(
      'Records of our administrators',
    );
    expect(screen.getByRole('region', { name: 'What we process and why' })).toHaveTextContent(
      'the reason they wrote stays with the resource until it is requeued',
    );
    expect(screen.getByRole('region', { name: 'What we process and why' })).toHaveTextContent(
      'one batch ID links those records',
    );
    expect(keep).toHaveTextContent(
      "administrators' actions in the back office, with their GitHub user ID and the reason or note they wrote: 24 months.",
    );
    expect(screen.getByRole('region', { name: 'Deleting your account' })).toHaveTextContent(
      'Delete my account',
    );
  });

  it('terms page states the disclaimer, the liability limit and links to privacy', async () => {
    mockApi();
    const { router } = renderApp('/terms');
    await screen.findByRole('heading', { level: 1, name: 'Terms of use' });
    await settle();
    await waitFor(() => expect(document.title).toBe('Terms · Helper2.ai'));
    expect(screen.getByRole('link', { name: 'helper2ai@incalza.me' })).toHaveAttribute(
      'href',
      'mailto:helper2ai@incalza.me',
    );
    expect(
      screen.getByRole('region', { name: 'No guarantee on results and answers' }),
    ).toHaveTextContent('can be wrong');
    expect(screen.getByRole('region', { name: 'Liability' })).toHaveTextContent('gross negligence');
    expect(screen.getByRole('region', { name: 'Law and place of jurisdiction' })).toHaveTextContent(
      'Swiss law',
    );

    await userEvent.click(screen.getByRole('link', { name: 'Privacy page' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/privacy'));
  });

  it.each(['/mission', '/privacy', '/terms'])('%s has no axe violations', async (path) => {
    mockApi();
    const { container } = renderApp(path);
    await screen.findByRole('heading', { level: 1 });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });
});
