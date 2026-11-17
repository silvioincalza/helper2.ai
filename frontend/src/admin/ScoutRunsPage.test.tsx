import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ScoutRun } from '@/api/types';
import { ADMIN } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';

const at = '2026-11-17T04:08:00Z';
const run = (sourceId: string, status: ScoutRun['status'], message: string | null = null) => ({
  id: `${sourceId}-run`,
  sourceId,
  status,
  startedAt: at,
  finishedAt: at,
  itemsSeen: 1,
  itemsChanged: 0,
  message,
});

describe('Feature: the Scout runs page shows one row per source', () => {
  it('Scenario: failures come first and skipped sources are hidden until asked for', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => [
        run('pypi', 'SUCCEEDED'),
        run('discovery-health', 'SKIPPED', 'Not due yet: next pass on 2026-11-23'),
        run('feed-nvidia', 'FAILED', 'HTTP 503'),
      ],
    });
    renderAdmin('/admin/runs');
    const table = await screen.findByRole('table', { name: 'Scout runs' });
    const sources = () =>
      within(table)
        .getAllByRole('rowheader')
        .map((c) => c.textContent);
    expect(sources()).toEqual(['feed-nvidia', 'pypi']);
    await userEvent.click(screen.getByRole('checkbox', { name: 'Show the 1 skipped source' }));
    expect(sources()).toEqual(['feed-nvidia', 'pypi', 'discovery-health']);
  });
});
