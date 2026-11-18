import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';

const MONGO = '11111111-1111-4111-8111-111111111111';
const PAPER = '22222222-2222-4222-8222-222222222222';

describe('Feature: the catalog checked against the AI word rule (S2.12.2)', () => {
  it('Scenario: the check lists what fails, page by page, and a waiting one is skipped with the reason', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/enrichment-queue': () => ({ items: [], next: null }),
      'GET /admin/ai-check': ({ url }) =>
        url.searchParams.get('after')
          ? { notAboutAi: [], checked: 120, next: null }
          : {
              notAboutAi: [
                {
                  resourceId: MONGO,
                  title: 'mongodb-connection-string-url',
                  type: 'PROMPT',
                  popularity: 0.912,
                  waiting: true,
                },
                {
                  resourceId: PAPER,
                  title: 'paper',
                  type: 'LIBRARY',
                  popularity: 0.4,
                  waiting: false,
                },
              ],
              checked: 500,
              next: 'abc',
            },
      [`POST /admin/enrichment-queue/${MONGO}/skip`]: () => undefined,
    });
    renderAdmin('/admin/enrichment');
    await userEvent.click(
      await screen.findByRole('button', { name: 'Check the 500 most popular' }),
    );
    expect(await screen.findByText('Checked 500 resources: 2 not about AI.')).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Not about AI by the word rule' });
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(within(table).queryByRole('button', { name: 'Skip paper' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Skip the \d+ waiting/ })).not.toBeInTheDocument();

    await userEvent.click(
      within(table).getByRole('button', { name: 'Skip mongodb-connection-string-url' }),
    );
    expect(m.callsTo('POST', `/admin/enrichment-queue/${MONGO}/skip`)[0]?.body).toEqual({
      reason: 'Not about AI (word rule)',
    });
    expect(await within(table).findByText('Skipped')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Check the next 500' }));
    expect(
      await screen.findByText(
        'Checked 620 resources: 2 not about AI. The whole catalog is checked.',
      ),
    ).toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/ai-check')[1]?.search).toContain('after=abc');
  });
});
