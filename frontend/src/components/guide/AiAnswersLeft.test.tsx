import { render, screen } from '@testing-library/react';
import type { Me } from '@/api/types';
import { ANON } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { settle } from '@/test/render';
import { MeProvider } from '@/context/MeContext';
import { AiAnswersLeft } from './AiAnswersLeft';

const renderWith = async (me: Me, answer: object | null = null) => {
  mockApi({ 'GET /me': () => me });
  const utils = render(
    <MeProvider>
      <AiAnswersLeft answer={answer} />
    </MeProvider>,
  );
  await settle();
  return utils;
};

describe('AI answers left today, where the guide answers', () => {
  it('shows nothing when the guide has no model (guideQuestions null)', async () => {
    const { container } = await renderWith({
      ...ANON,
      limits: { ...ANON.limits, guideQuestions: null },
    });
    expect(container).toBeEmptyDOMElement();
  });

  it('says how many are left, from GET /me', async () => {
    await renderWith({
      ...ANON,
      limits: { ...ANON.limits, guideQuestions: { remaining: 2, limit: 3 } },
    });
    expect(screen.getByText(/2 of 3 AI answers left today/)).toBeInTheDocument();
  });

  it('at none, says when they come back and that search still works', async () => {
    await renderWith({
      ...ANON,
      limits: {
        ...ANON.limits,
        guideQuestions: { remaining: 0, limit: 3 },
        resetsAt: '2026-11-06T00:00:00Z',
      },
    });
    expect(
      screen.getByText(
        /No AI answers left today\. They come back at 00:00 UTC; search still works\./,
      ),
    ).toBeInTheDocument();
  });
});

describe('after an answer', () => {
  it('reads GET /me again, so the count is the server’s', async () => {
    const m = mockApi({ 'GET /me': () => ANON });
    const { rerender } = render(
      <MeProvider>
        <AiAnswersLeft answer={null} />
      </MeProvider>,
    );
    await settle();
    expect(m.callsTo('GET', '/me')).toHaveLength(1);
    rerender(
      <MeProvider>
        <AiAnswersLeft answer={{ status: 'OK' }} />
      </MeProvider>,
    );
    await settle();
    expect(m.callsTo('GET', '/me')).toHaveLength(2);
  });
});
