import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockApi, problem } from '@/test/mockApi';
import { CategoryBackfill } from './CategoryBackfill';

describe('admin: assign categories again (ADR 0026)', () => {
  it('starts the backfill and says it runs in the background', async () => {
    const api = mockApi({
      'POST /admin/category-backfill': () => new Response(null, { status: 202 }),
    });
    render(<CategoryBackfill />);

    await userEvent.click(screen.getByRole('button', { name: 'Assign categories again' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Started.');
    expect(api.callsTo('POST', '/admin/category-backfill')).toHaveLength(1);
  });

  it('says so when it could not start', async () => {
    mockApi({ 'POST /admin/category-backfill': () => problem(500, 'Failed') });
    render(<CategoryBackfill />);

    await userEvent.click(screen.getByRole('button', { name: 'Assign categories again' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Could not start it.');
  });
});
