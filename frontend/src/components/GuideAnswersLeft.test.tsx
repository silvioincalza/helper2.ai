import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse, Me } from '@/api/types';
import { mockApi, sse } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { axeViolations } from '@/test/a11y';
import { ANON, USER, makeStatus } from '@/test/fixtures';

const BOX = 'Search AI resources';
const QUESTION = 'an MCP server for Postgres';

function withGuide(me: Me, guideQuestions: Me['limits']['guideQuestions']): Me {
  return { ...me, limits: { ...me.limits, guideQuestions } };
}

const ANSWER: GuideResponse = {
  status: 'OK',
  searchQuery: 'mcp server postgres',
  recommendations: [],
  prompt: null,
  model: {
    provider: 'nebius-token-factory',
    fastModel: 'nvidia/nemotron-nano',
    reasoningModel: 'nvidia/nemotron-3-super',
  },
  aiContent: {
    aiGenerated: true,
    provider: 'nebius-token-factory',
    models: ['nvidia/nemotron-nano', 'nvidia/nemotron-3-super'],
    fields: ['searchQuery'],
    policy: '/mission',
  },
  candidatesConsidered: 12,
  rejected: 0,
  sources: [makeStatus()],
  notices: [],
  generatedAt: '2026-10-25T10:00:00Z',
};

async function typeQuestion(text = QUESTION) {
  await userEvent.type(await screen.findByLabelText(BOX), text);
}

describe('Feature: AI answers left today, next to "Ask the guide"', () => {
  it('Scenario: a guest sees the count once the button asks, and the box is described by it', async () => {
    mockApi({ 'GET /me': () => withGuide(ANON, { limit: 3, remaining: 2 }) });
    renderApp('/');
    await settle();
    await userEvent.type(await screen.findByLabelText(BOX), 'rag');
    // A few words search: no AI answer is at stake, so nothing is said.
    expect(screen.queryByText(/AI answers left today/)).toBeNull();

    await userEvent.type(screen.getByLabelText(BOX), ' for my own notes');
    const line = screen.getByText('2 of 3 AI answers left today.');
    expect(screen.getByLabelText(BOX)).toHaveAttribute(
      'aria-describedby',
      expect.stringContaining(line.id),
    );
    expect(await axeViolations(document.body)).toEqual([]);
  });

  it('Scenario: after an answer the count is read again from /me', async () => {
    let remaining = 3;
    const m = mockApi({
      'GET /me': () => withGuide(ANON, { limit: 3, remaining }),
      'POST /guide/stream': () => {
        remaining -= 1;
        return sse(ANSWER);
      },
    });
    renderApp('/');
    await settle();
    await typeQuestion();
    expect(screen.getByText('3 of 3 AI answers left today.')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));
    expect(await screen.findByText('2 of 3 AI answers left today.')).toBeInTheDocument();
    expect(m.callsTo('GET', '/me')).toHaveLength(2);
  });

  it('Scenario: none left says so in words, with the reset time, and search still works', async () => {
    mockApi({ 'GET /me': () => withGuide(ANON, { limit: 3, remaining: 0 }) });
    const { router } = renderApp('/');
    await settle();
    await typeQuestion();
    const line = screen.getByText(/^No AI answers left today\./);
    expect(line).toHaveTextContent(/without AI picks until \d{2}:\d{2} your time\.$/);
    // The API does not say a signed-in user gets more questions, so nothing offers it.
    expect(line).not.toHaveTextContent(/sign in/i);

    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
  });

  it('Scenario: nothing is said when the guide has no model, or for a test ceiling', async () => {
    mockApi({ 'GET /me': () => withGuide(ANON, null) });
    renderApp('/');
    await settle();
    await typeQuestion();
    expect(screen.getByRole('button', { name: 'Ask the guide' })).toBeEnabled();
    expect(screen.queryByText(/AI answers left today/)).toBeNull();
  });

  it('Scenario: a high test ceiling is not said while answers are left', async () => {
    mockApi({ 'GET /me': () => withGuide(ANON, { limit: 1000, remaining: 998 }) });
    renderApp('/');
    await settle();
    await typeQuestion();
    expect(screen.queryByText(/AI answers left today/)).toBeNull();
  });

  it('Scenario: a signed-in user with a limit sees it too (the limit is per address)', async () => {
    mockApi({ 'GET /me': () => withGuide(USER, { limit: 30, remaining: 29 }) });
    renderApp('/');
    await settle();
    await typeQuestion();
    expect(screen.getByText('29 of 30 AI answers left today.')).toBeInTheDocument();
  });
});
