import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const box = () => screen.getByRole('textbox', { name: 'Search AI resources' }) as HTMLTextAreaElement;

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
    await userEvent.type(await screen.findByRole('textbox', { name: 'Search AI resources' }), 'vector db');
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

  it('Scenario: near the limit the box says how many characters are left', async () => {
    mockApi();
    renderApp('/');
    await screen.findByRole('textbox', { name: 'Search AI resources' });
    fireEvent.change(box(), { target: { value: 'a '.repeat(220) } });
    expect(screen.queryByText(/characters left/)).toBeNull();
    fireEvent.change(box(), { target: { value: 'a '.repeat(240) } });
    expect(screen.getByText('20 characters left')).toBeInTheDocument();
  });
});
