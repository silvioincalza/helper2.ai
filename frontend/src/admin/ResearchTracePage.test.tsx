import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';
import { parseEvents } from './ResearchTracePage';

const RUN = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const ITEM = '11111111-1111-4111-8111-111111111111';

const run = (status: string, steps: number) => ({
  id: RUN,
  itemId: ITEM,
  status,
  model: 'nvidia/Nemotron-3.5-Super',
  requestedAt: '2026-11-18T02:00:00Z',
  startedAt: '2026-11-18T02:00:01Z',
  finishedAt: status === 'RUNNING' ? null : '2026-11-18T02:00:42Z',
  steps,
  promptTokens: 2104,
  completionTokens: 61,
  verdict: status === 'DONE' ? 'KEEP' : null,
  error: null,
});

const step = (seq: number, kind: string, name: string, request: string, response: string) => ({
  seq,
  kind,
  name,
  request,
  response,
  error: null,
  durationMs: 1400,
  promptTokens: kind === 'MODEL_CALL' ? 2104 : null,
  completionTokens: kind === 'MODEL_CALL' ? 61 : null,
  at: '2026-11-18T02:00:02Z',
});

describe('admin: every step of a research run (ADR 0048)', () => {
  it('Scenario: each request and response is shown as plain text, never as a page', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      [`GET /admin/review-research/${RUN}`]: () => ({
        run: run('DONE', 2),
        result: null,
        steps: [
          step(
            1,
            'MODEL_CALL',
            'nvidia/Nemotron-3.5-Super',
            '[user] Review item',
            '[tool call read_page]',
          ),
          step(
            2,
            'TOOL_CALL',
            'read_page',
            '{"url":"https://acmenotes.example"}',
            '<script>alert(1)</script><b>Acme Notes</b>',
          ),
        ],
      }),
    });
    renderAdmin(`/admin/review/research/${RUN}`);
    expect(
      await screen.findByRole('heading', { name: 'Every step of the research' }),
    ).toBeInTheDocument();
    expect(screen.getByText('<script>alert(1)</script><b>Acme Notes</b>')).toBeInTheDocument();
    expect(document.querySelector('b')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Tool call' }));
    const steps = within(screen.getByRole('list', { name: 'Steps' })).getAllByRole('listitem');
    expect(steps).toHaveLength(1);
    expect(within(steps[0] as HTMLElement).getByText('read_page')).toBeInTheDocument();
  });

  it('Scenario: the steps of a running research arrive as they are stored', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      [`GET /admin/review-research/${RUN}`]: () => ({
        run: run('RUNNING', 1),
        result: null,
        steps: [
          step(1, 'MODEL_CALL', 'nvidia/Nemotron-3.5-Super', '[user] Review item', '[tool call]'),
        ],
      }),
      [`GET /admin/review-research/${RUN}/events`]: () =>
        new Response(
          `event:step\ndata:${JSON.stringify(step(2, 'TOOL_CALL', 'web_search', '{"query":"Acme"}', '1. Acme'))}\n\n`,
          { status: 200, headers: { 'content-type': 'text/event-stream' } },
        ),
    });
    renderAdmin(`/admin/review/research/${RUN}`);
    expect(await screen.findByText('web_search')).toBeInTheDocument();
    expect(m.callsTo('GET', `/admin/review-research/${RUN}/events`)[0]?.search).toBe('?afterSeq=1');
  });

  it('reads server-sent events across chunks', () => {
    const first = parseEvents('event:step\ndata:{"seq":1}\n\nevent:sta');
    expect(first.events).toEqual([{ event: 'step', data: '{"seq":1}' }]);
    const second = parseEvents(`${first.rest}tus\ndata:{"status":"DONE"}\n\n`);
    expect(second.events).toEqual([{ event: 'status', data: '{"status":"DONE"}' }]);
  });
});
