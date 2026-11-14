import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const LIVE_WAIT_MS = 800;

const box = () =>
  screen.getByRole('textbox', { name: 'Search AI resources' }) as HTMLTextAreaElement;

const pendingGuide = () => new Promise<Response>(() => {});

const waitPastLiveSearch = () =>
  act(() => new Promise((resolve) => setTimeout(resolve, LIVE_WAIT_MS)));

describe('Feature: the box and an asked question', () => {
  it('Scenario: Enter with new words searches them without the old question', async () => {
    mockApi({ 'POST /guide/stream': pendingGuide });
    const { params } = renderApp('/search?q=new+tools&ask=new+tools');
    await screen.findByRole('textbox', { name: 'Search AI resources' });

    await userEvent.clear(box());
    await userEvent.type(box(), 'speech models{Enter}');

    expect(params().get('q')).toBe('speech models');
    expect(params().has('ask')).toBe(false);
  });

  it('Scenario: Enter with the asked words keeps the question', async () => {
    mockApi({ 'POST /guide/stream': pendingGuide });
    const { params } = renderApp('/search?q=new+tools&ask=new+tools&page=1');
    await screen.findByRole('textbox', { name: 'Search AI resources' });

    await userEvent.type(box(), '{Enter}');

    expect(params().get('ask')).toBe('new tools');
  });

  it('Scenario: typing while the guide answers does not stop it; Enter searches and Back returns to it', async () => {
    const m = mockApi({ 'POST /guide/stream': pendingGuide });
    const { params, router } = renderApp('/search?q=new+tools&ask=new+tools');
    await screen.findByRole('textbox', { name: 'Search AI resources' });

    fireEvent.change(box(), { target: { value: 'speech models' } });
    await waitPastLiveSearch();

    expect(params().get('ask')).toBe('new tools');
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);

    await userEvent.type(box(), '{Enter}');
    expect(params().has('ask')).toBe(false);
    await act(() => router.navigate(-1));
    expect(params().get('ask')).toBe('new tools');
  });

  it('Scenario: Back and Forward between two questions with the same first 200 characters show each question in the box', async () => {
    mockApi({ 'POST /guide/stream': pendingGuide });
    const shared = 'an MCP server that works with Postgres '.repeat(6).slice(0, 200);
    const first = `${shared} and runs in a container`;
    const second = `${shared} and needs no account`;
    const url = (ask: string) =>
      `/search?q=${encodeURIComponent(shared.trimEnd())}&ask=${encodeURIComponent(ask)}`;
    const { router } = renderApp(url(first));
    await screen.findByRole('textbox', { name: 'Search AI resources' });
    expect(box().value).toBe(first);

    await act(() => router.navigate(url(second)));
    expect(box().value).toBe(second);

    await act(() => router.navigate(-1));
    expect(box().value).toBe(first);

    await act(() => router.navigate(1));
    expect(box().value).toBe(second);
  });

  it('Scenario: a keystroke right after a live search is kept in the box', async () => {
    mockApi();
    const { params } = renderApp('/search?q=postgres');
    await screen.findByRole('textbox', { name: 'Search AI resources' });
    await settle();

    fireEvent.change(box(), { target: { value: 'postgres mcp' } });
    await act(
      () =>
        new Promise<void>((resolve) => {
          setTimeout(() => {
            fireEvent.change(box(), { target: { value: 'postgres mcp s' } });
            resolve();
          }, 700);
        }),
    );
    await settle();

    expect(params().get('q')).toBe('postgres mcp');
    expect(box().value).toBe('postgres mcp s');
  });

  it('Scenario: on a phone the ask button shows a short label and the keyboard offers Search', async () => {
    mockApi();
    renderApp('/');
    await screen.findByRole('textbox', { name: 'Search AI resources' });

    expect(screen.getByRole('button', { name: 'Ask the guide' })).toHaveTextContent('Ask');
    expect(box()).toHaveAttribute('enterkeyhint', 'search');
  });

  it('Scenario: g goes to Ask the guide and spends no AI answer; Enter there asks the whole question in the box, also when the search took only its first 200 characters', async () => {
    const m = mockApi({ 'POST /guide/stream': pendingGuide });
    const question = `an MCP server ${'that works with Postgres '.repeat(12)}`
      .slice(0, 290)
      .trimEnd();
    const q = question.slice(0, 200).trimEnd();
    const { params } = renderApp(`/search?q=${encodeURIComponent(q)}`);
    const first = await waitFor(() => {
      const link = document.querySelector<HTMLAnchorElement>('.h2-row__title');
      expect(link).not.toBeNull();
      return link!;
    });
    await settle();
    fireEvent.change(box(), { target: { value: question } });

    first.focus();
    await userEvent.keyboard('g');

    expect(screen.getByRole('button', { name: 'Ask the guide' })).toHaveFocus();
    expect(params().get('ask')).toBeNull();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);

    await userEvent.keyboard('{Enter}');

    expect(params().get('ask')).toBe(question);
    expect(params().get('q')).toBe(q);
  });
});
