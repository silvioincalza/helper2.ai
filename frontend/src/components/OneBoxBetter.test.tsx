import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { axeViolations } from '@/test/a11y';

const box = () =>
  screen.getByRole('textbox', { name: 'Search AI resources' }) as HTMLTextAreaElement;

describe('Feature: a better search box', () => {
  it('Scenario: × empties the box and keeps the results; it shows only when there is text', async () => {
    mockApi();
    const { router } = renderApp('/search?q=postgres');
    await screen.findByRole('textbox', { name: 'Search AI resources' });
    await settle();
    expect(box().value).toBe('postgres');
    await userEvent.click(screen.getByRole('button', { name: 'Clear the box' }));
    expect(box().value).toBe('');
    expect(box()).toHaveFocus();
    expect(router.state.location.search).toBe('?q=postgres');
    expect(screen.queryByRole('button', { name: 'Clear the box' })).toBeNull();
  });

  it('Scenario: Esc empties the box', async () => {
    mockApi();
    renderApp('/');
    await userEvent.type(
      await screen.findByRole('textbox', { name: 'Search AI resources' }),
      'vector db',
    );
    await userEvent.keyboard('{Escape}');
    expect(box().value).toBe('');
  });

  it('Scenario: "/" puts the cursor in the box, but not while typing in another field', async () => {
    mockApi();
    renderApp('/sources');
    await screen.findByRole('textbox', { name: 'Search AI resources' });
    await settle();
    (document.activeElement as HTMLElement | null)?.blur();
    await userEvent.keyboard('/');
    expect(box()).toHaveFocus();
    expect(box().value).toBe('');

    const other = document.createElement('input');
    document.body.appendChild(other);
    other.focus();
    await userEvent.keyboard('/');
    expect(other).toHaveFocus();
    other.remove();
  });

  it('Scenario: Search with an empty box puts the cursor in the box with a hint, and searches nothing', async () => {
    const m = mockApi();
    const { router } = renderApp('/');
    await screen.findByRole('textbox', { name: 'Search AI resources' });
    await settle();
    const button = screen.getByRole('button', { name: 'Search' });
    expect(button).toBeEnabled();
    await userEvent.click(button);

    const hint = screen.getByRole('status');
    expect(hint).toHaveTextContent('Type what you need first.');
    expect(box()).toHaveFocus();
    expect(box()).toHaveAttribute('aria-describedby', expect.stringContaining(hint.id));
    expect(router.state.location.pathname).toBe('/');
    expect(m.callsTo('GET', '/search')).toHaveLength(0);
    expect(await axeViolations(document.body)).toEqual([]);

    // A second press keeps the hint and the cursor; typing takes the hint away.
    // Inside act: the radar on Home holds still while the box has focus and listens for it to leave.
    act(() => (document.activeElement as HTMLElement | null)?.blur());
    await userEvent.click(button);
    expect(box()).toHaveFocus();
    await userEvent.type(box(), 'rag');
    expect(screen.queryByText('Type what you need first.')).toBeNull();
    expect(box()).not.toHaveAttribute('aria-describedby');
  });

  it('Scenario: only spaces count as an empty box too', async () => {
    const m = mockApi();
    const { router } = renderApp('/');
    await userEvent.type(
      await screen.findByRole('textbox', { name: 'Search AI resources' }),
      '   ',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(screen.getByText('Type what you need first.')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(m.callsTo('GET', '/search')).toHaveLength(0);
  });

  it('Scenario: Enter in an empty box on the results page still goes back to the empty home page', async () => {
    mockApi();
    const { router } = renderApp('/search?q=postgres');
    await screen.findByRole('textbox', { name: 'Search AI resources' });
    await settle();
    await userEvent.clear(box());
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(screen.queryByText('Type what you need first.')).toBeNull();
  });

  it('Scenario: near the limit the box says how many characters are left', async () => {
    mockApi();
    renderApp('/');
    await screen.findByRole('textbox', { name: 'Search AI resources' });
    fireEvent.change(box(), { target: { value: 'a '.repeat(220) } });
    expect(screen.queryByText(/characters left/)).toBeNull();
    fireEvent.change(box(), { target: { value: 'a '.repeat(240) } });
    // Inside the box: short on screen, the full sentence for screen readers.
    const left = screen.getByText('20 characters left').closest('.searchbox-left')!;
    expect(left.closest('form')).not.toBeNull();
    expect(left).toHaveTextContent('20 left');
  });
});
