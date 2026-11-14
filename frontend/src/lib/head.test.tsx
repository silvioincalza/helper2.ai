import { act, screen } from '@testing-library/react';
import { ANON } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';

function serverTag(html: string) {
  const tpl = document.createElement('template');
  tpl.innerHTML = html;
  document.head.append(tpl.content);
}

describe('the head the server wrote for crawlers (ADR 0041)', () => {
  afterEach(() => document.head.querySelectorAll('[data-h2-head]').forEach((el) => el.remove()));

  it('stays on the page the visitor landed on and goes at the first move to another page', async () => {
    mockApi({ 'GET /me': () => ANON });
    serverTag('<meta data-h2-head name="description" content="About Helper2.ai">');
    serverTag('<link data-h2-head rel="canonical" href="https://helper2.ai/mission">');
    const { router } = renderApp('/mission');
    await screen.findByRole('heading', { level: 1 });
    expect(document.head.querySelectorAll('[data-h2-head]')).toHaveLength(2);

    await act(() => router.navigate('/terms'));
    await screen.findByRole('heading', { level: 1, name: /Terms/ });
    expect(document.head.querySelectorAll('[data-h2-head]')).toHaveLength(0);
  });
});
