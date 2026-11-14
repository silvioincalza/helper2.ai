import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Me, ToolGuide } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { ANON, USER, makeCard, makeDetail } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { visitorIn } from '@/test/visitor';

const PG = makeCard({
  id: 'bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb',
  title: 'Postgres MCP',
  type: 'MCP_SERVER',
  url: 'https://github.com/acme/postgres-mcp',
});
const PAGE = `/resource/${PG.id}`;
const RESOURCE = `GET /resources/${PG.id}`;
const SETUP = `GET /resources/${PG.id}/setup`;
const GUIDE = `POST /resources/${PG.id}/guide`;

const NO_CONNECT = {
  resourceId: PG.id,
  title: PG.title,
  version: null,
  options: [],
  clients: [],
  sources: [],
};

const WRITTEN: ToolGuide = {
  resourceId: PG.id,
  title: PG.title,
  status: 'OK',
  rejected: 0,
  generatedAt: '2026-11-12T10:00:00Z',
  model: 'nemotron-nano',
  sections: [
    {
      kind: 'INSTALL',
      steps: [
        {
          text: 'Install it with npm.',
          quote: 'Install globally:',
          source: 'readme',
          caution: false,
        },
      ],
    },
  ],
  documents: [
    {
      key: 'readme',
      kind: 'README',
      url: 'https://github.com/acme/postgres-mcp',
      retrievedAt: '2026-11-12T10:00:00Z',
    },
  ],
};

const REFUSED: ToolGuide = {
  ...WRITTEN,
  status: 'LLM_UNAVAILABLE',
  unavailable: 'quota',
  retryAt: '2026-11-13T00:00:00Z',
  model: null,
  sections: [],
};

function withSetup(me: Me, setupGuides: Me['limits']['setupGuides']): Me {
  return { ...me, limits: { ...me.limits, setupGuides, resetsAt: '2026-11-13T00:00:00Z' } };
}

function setItUp() {
  return screen.findByRole('button', { name: 'Set it up' });
}

describe('Feature: AI setups left today, next to "Set it up"', () => {
  it('Scenario: the count is said before the click, the button is described by it, and no guide is asked for', async () => {
    const m = mockApi({
      [RESOURCE]: () => makeDetail({ card: PG }),
      'GET /me': () => withSetup(ANON, { limit: 20, remaining: 18 }),
    });
    const { container } = renderApp(PAGE);
    const line = await screen.findByText(
      '18 of 20 AI setups left today. Steps that are already written do not count.',
    );
    expect(await setItUp()).toHaveAttribute('aria-describedby', line.id);
    expect(m.callsTo('POST', `/resources/${PG.id}/guide`)).toHaveLength(0);
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: after a guide is written the count is read again from /me', async () => {
    let remaining = 20;
    const m = mockApi({
      [RESOURCE]: () => makeDetail({ card: PG }),
      [SETUP]: () => NO_CONNECT,
      'GET /me': () => withSetup(ANON, { limit: 20, remaining }),
      [GUIDE]: () => {
        remaining -= 1;
        return WRITTEN;
      },
    });
    renderApp(PAGE);
    expect(await screen.findByText(/^20 of 20 AI setups left today\./)).toBeInTheDocument();

    await userEvent.click(await setItUp());
    expect(await screen.findByText('Install it with npm.')).toBeInTheDocument();
    expect(await screen.findByText(/^19 of 20 AI setups left today\./)).toBeInTheDocument();
    expect(m.callsTo('GET', '/me')).toHaveLength(2);

    await userEvent.click(await setItUp());
    await userEvent.click(await setItUp());
    await settle();
    expect(m.callsTo('POST', `/resources/${PG.id}/guide`)).toHaveLength(1);
    expect(m.callsTo('GET', '/me')).toHaveLength(2);
  });

  it('Scenario: none left says so in words, with the reset time, and the button still opens what is written', async () => {
    visitorIn('it-CH', 'Europe/Zurich');
    const m = mockApi({
      [RESOURCE]: () => makeDetail({ card: PG }),
      [SETUP]: () => NO_CONNECT,
      'GET /me': () => withSetup(ANON, { limit: 20, remaining: 0 }),
      [GUIDE]: () => REFUSED,
    });
    renderApp(PAGE);
    const line = await screen.findByText(/^No AI setups left today\./);
    expect(line).toHaveTextContent(
      /Steps already written still open; new ones are back at 01:00 your time\.$/,
    );
    expect(line).not.toHaveClass('muted');
    expect(line).not.toHaveTextContent(/sign in/i);
    const button = await setItUp();
    expect(button).toBeEnabled();
    expect(button).toHaveAttribute('aria-describedby', line.id);

    await userEvent.click(button);
    await waitFor(() => expect(m.callsTo('POST', `/resources/${PG.id}/guide`)).toHaveLength(1));
    expect(
      await screen.findByText(/You have reached today's limit for new setup steps\./),
    ).toBeInTheDocument();
  });

  it('Scenario: nothing is said when the setup guide has no model, and the button has no description', async () => {
    mockApi({
      [RESOURCE]: () => makeDetail({ card: PG }),
      'GET /me': () => withSetup(ANON, null),
    });
    renderApp(PAGE);
    const button = await setItUp();
    await settle();
    expect(screen.queryByText(/AI setups left today/)).toBeNull();
    expect(button).not.toHaveAttribute('aria-describedby');
    expect(button).toBeEnabled();
  });

  it('Scenario: a high test ceiling is not said while guides are left', async () => {
    mockApi({
      [RESOURCE]: () => makeDetail({ card: PG }),
      'GET /me': () => withSetup(ANON, { limit: 1000, remaining: 998 }),
    });
    renderApp(PAGE);
    await setItUp();
    await settle();
    expect(screen.queryByText(/AI setups left today/)).toBeNull();
  });

  it('Scenario: a signed-in user sees the count too (the limit is per address)', async () => {
    mockApi({
      [RESOURCE]: () => makeDetail({ card: PG }),
      'GET /me': () => withSetup(USER, { limit: 20, remaining: 5 }),
    });
    renderApp(PAGE);
    expect(await screen.findByText(/^5 of 20 AI setups left today\./)).toBeInTheDocument();
  });
});
