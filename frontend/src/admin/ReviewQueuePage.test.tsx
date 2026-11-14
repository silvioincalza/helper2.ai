import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';

function candidateItem(details: string) {
  return {
    items: [
      {
        id: '55555555-5555-5555-8555-555555555555',
        kind: 'RESOURCE_CANDIDATE',
        status: 'OPEN',
        reason: 'New PRODUCT (Self-hosted) found by discovery-self-hosted: Open WebUI',
        details,
        resourceId: null,
        resourceTitle: null,
        createdAt: '2026-11-05T03:20:00Z',
      },
    ],
    total: 1,
  };
}

describe('admin: resource candidates in the review queue', () => {
  it('shows the page as a link, its type, its categories and the search excerpt', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () =>
        candidateItem(
          JSON.stringify({
            sourceId: 'discovery-self-hosted',
            url: 'https://openwebui.com/',
            title: 'Open WebUI',
            type: 'PRODUCT',
            tags: ['local-ai', 'self-hosted'],
            snippet: 'Self-hosted AI platform that runs offline.',
          }),
        ),
    });
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE');
    const link = await screen.findByRole('link', { name: 'Open WebUI' });
    expect(link).toHaveAttribute('href', 'https://openwebui.com/');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer nofollow');
    const candidate = link.closest('dl') as HTMLElement;
    expect(candidate).toHaveTextContent('Product');
    expect(candidate).toHaveTextContent('local-ai, self-hosted');
    expect(candidate).toHaveTextContent('Self-hosted AI platform that runs offline.');
    expect(candidate).toHaveTextContent('Accept adds it to the catalog');
  });

  it('never links a page that is not https, and shows the raw details instead', async () => {
    const details = JSON.stringify({
      url: 'javascript:alert(1)',
      title: 'Bad',
      type: 'PRODUCT',
    });
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => candidateItem(details),
    });
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE');
    expect(await screen.findByText(details)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Bad' })).not.toBeInTheDocument();
  });
});

describe('admin: known target proposals in the review queue', () => {
  const proposal = (details: string) => ({
    items: [
      {
        id: '66666666-6666-4666-8666-666666666666',
        kind: 'KNOWN_TARGET_CANDIDATE',
        status: 'OPEN',
        reason: 'Owner of 22 GitHub or Hugging Face resources: deepseek-ai',
        details,
        resourceId: null,
        resourceTitle: null,
        createdAt: '2026-11-12T03:20:00Z',
      },
    ],
    total: 1,
  });

  it('shows the name, the kind, the rule with its count and the examples', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () =>
        proposal(
          JSON.stringify({
            key: 'deepseek-ai',
            name: 'deepseek-ai',
            kind: 'VENDOR',
            rule: 'OWNER',
            count: 22,
            otherNames: [],
            examples: [
              { id: '77777777-7777-4777-8777-777777777777', title: 'deepseek-ai/DeepSeek-V3' },
              { id: '88888888-8888-4888-8888-888888888888', title: 'deepseek-ai/Janus' },
            ],
          }),
        ),
    });
    renderAdmin('/admin/review?kind=KNOWN_TARGET_CANDIDATE');
    const name = await screen.findByText('deepseek-ai', { selector: 'dd' });
    const card = name.closest('dl') as HTMLElement;
    expect(card).toHaveTextContent('Vendor');
    expect(card).toHaveTextContent('22 GitHub or Hugging Face resources have this owner');
    expect(card).toHaveTextContent('deepseek-ai/DeepSeek-V3, deepseek-ai/Janus');
    expect(card).toHaveTextContent('Accept adds it to the known vendors and services');
  });

  it('saves a changed name, kind and other names, and reloads the list', async () => {
    const sent: unknown[] = [];
    let details = JSON.stringify({
      key: 'meta-llama',
      name: 'meta-llama',
      kind: 'VENDOR',
      rule: 'OWNER',
      count: 22,
      otherNames: [],
      examples: [],
    });
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => proposal(details),
      'PUT /admin/review-items/66666666-6666-4666-8666-666666666666/known-target': (req) => {
        const body = req.body as { name: string; otherNames: string[] };
        sent.push(body);
        details = JSON.stringify({
          key: 'meta-llama',
          name: body.name,
          kind: 'VENDOR',
          rule: 'OWNER',
          count: 22,
          otherNames: body.otherNames,
          examples: [],
          edited: true,
        });
        return proposal(details).items[0];
      },
    });
    const user = userEvent.setup();
    renderAdmin('/admin/review?kind=KNOWN_TARGET_CANDIDATE');
    await user.click(await screen.findByRole('button', { name: 'Change the proposal' }));
    const name = screen.getByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Meta');
    await user.type(screen.getByLabelText(/Other names/), 'meta-llama{Enter}Meta AI');
    await user.click(screen.getByRole('button', { name: 'Save the changes' }));

    const shown = await screen.findByText('Meta', { selector: 'dd' });
    expect(sent).toEqual([{ kind: 'VENDOR', name: 'Meta', otherNames: ['meta-llama', 'Meta AI'] }]);
    const card = shown.closest('dl') as HTMLElement;
    expect(card).toHaveTextContent('meta-llama, Meta AI');
    expect(card).toHaveTextContent('Changed by an admin.');
  });

  it('shows the raw details of a proposal it cannot read', async () => {
    const details = JSON.stringify({ name: 'x', kind: 'PRODUCT' });
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => proposal(details),
    });
    renderAdmin('/admin/review?kind=KNOWN_TARGET_CANDIDATE');
    expect(await screen.findByText(details)).toBeInTheDocument();
  });
});
