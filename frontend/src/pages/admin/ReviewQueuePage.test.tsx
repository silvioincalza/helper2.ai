import { screen } from '@testing-library/react';
import { ADMIN } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';

function candidateItem(details: string) {
  return {
    items: [
      {
        id: '55555555-5555-5555-8555-555555555555',
        kind: 'RESOURCE_CANDIDATE',
        status: 'OPEN',
        reason: 'New PRODUCT (Self-hosted) found by tavily-discovery: Open WebUI',
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
            sourceId: 'tavily-discovery',
            url: 'https://openwebui.com/',
            title: 'Open WebUI',
            type: 'PRODUCT',
            tags: ['local-ai', 'self-hosted'],
            snippet: 'Self-hosted AI platform that runs offline.',
          }),
        ),
    });
    renderApp('/admin/review?kind=RESOURCE_CANDIDATE');
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
    renderApp('/admin/review?kind=RESOURCE_CANDIDATE');
    expect(await screen.findByText(details)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Bad' })).not.toBeInTheDocument();
  });
});
