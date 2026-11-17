import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';

const cursor = {
  key: 'cursor',
  name: 'Cursor',
  items: 117,
  kinds: ['INTEGRATES_WITH', 'USES'],
  examples: ['Figma MCP', 'Linear MCP', 'Sentry MCP'],
};
const pytorch = { ...cursor, key: 'pytorch', name: 'PyTorch', items: 58, examples: ['timm'] };

function groupsApi(decided = 117) {
  let groups = [cursor, pytorch];
  return mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/review-items': () => ({ items: [], total: 10932 }),
    'GET /admin/relationship-targets': () => ({ items: groups, total: groups.length }),
    'POST /admin/relationship-targets/decision': ({ body }) => {
      const key = (body as { key: string }).key;
      groups = groups.filter((g) => g.key !== key);
      return { batch: '11111111-1111-4111-8111-111111111111', decided, skipped: 0, left: 0 };
    },
    'GET /search': () => ({
      results: [{ id: '22222222-2222-4222-8222-222222222222', title: 'PyTorch', type: 'LIBRARY' }],
    }),
  });
}

describe('admin: relationship candidates by target name (S6.2.10)', () => {
  it('Scenario: the relationship candidates open grouped by target name, the largest first', async () => {
    groupsApi();
    renderAdmin('/admin/review?kind=RELATIONSHIP_CANDIDATE');
    const first = await screen.findByRole('button', { name: 'Cursor' });
    expect(first).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: 'By target name' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    const detail = screen.getByRole('region', { name: 'Cursor' });
    expect(detail).toHaveTextContent('117 open items · integrates with, uses');
    expect(detail).toHaveTextContent('Figma MCP, Linear MCP, Sentry MCP');
  });

  it('Scenario: one click marks every item of the name as a service, and the next name is shown', async () => {
    const m = groupsApi();
    renderAdmin('/admin/review?kind=RELATIONSHIP_CANDIDATE');
    await userEvent.click(
      await screen.findByRole('button', { name: 'It is a service (117 items)' }),
    );
    expect(m.callsTo('POST', '/admin/relationship-targets/decision')[0]?.body).toEqual({
      key: 'cursor',
      decision: 'SERVICE',
    });
    expect(
      await screen.findByText('Cursor: 117 items decided, 0 skipped, 0 left.'),
    ).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'PyTorch' })).toBeInTheDocument();
  });

  it('Scenario: linking a name to a catalog resource sends the resource for the whole group', async () => {
    const m = groupsApi(58);
    renderAdmin('/admin/review?kind=RELATIONSHIP_CANDIDATE');
    await userEvent.click(await screen.findByRole('button', { name: 'PyTorch' }));
    const detail = screen.getByRole('region', { name: 'PyTorch' });
    await userEvent.click(
      within(detail).getByRole('button', { name: 'It is a resource in the catalog…' }),
    );
    await userEvent.click(within(detail).getByRole('button', { name: 'Find' }));
    await userEvent.click(
      await within(detail).findByRole('button', { name: 'Point at it PyTorch' }),
    );
    expect(m.callsTo('POST', '/admin/relationship-targets/decision')[0]?.body).toEqual({
      key: 'pytorch',
      decision: 'LINK',
      resourceId: '22222222-2222-4222-8222-222222222222',
    });
  });

  it('Scenario: "Each item" shows the items one by one, as before', async () => {
    const m = groupsApi();
    renderAdmin('/admin/review?kind=RELATIONSHIP_CANDIDATE&view=items');
    expect(await screen.findByRole('button', { name: 'Each item' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(m.callsTo('GET', '/admin/relationship-targets')).toHaveLength(0);
  });
});
