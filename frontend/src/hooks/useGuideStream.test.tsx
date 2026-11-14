import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useNavigate } from 'react-router';
import { api } from '@/api/client';
import type { GuideResponse } from '@/api/types';
import { clearApiCache } from './useApi';
import { useGuideStream } from './useGuideStream';

const at =
  (url: string) =>
  ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[url]}>{children}</MemoryRouter>
  );

const renderStream = (url: string) =>
  renderHook(() => ({ stream: useGuideStream(), navigate: useNavigate() }), {
    wrapper: at(url),
  });

const BUDGET_SPENT = {
  code: 'GUIDE_BUDGET_SPENT',
  message: 'No more for now.',
} as GuideResponse['notices'][number];

const pending = () => new Promise<GuideResponse>(() => {});

const answer = (status: GuideResponse['status'], notices: GuideResponse['notices'] = []) =>
  ({ status, notices }) as unknown as GuideResponse;

const QUOTA = {
  code: 'GUIDE_DAILY_QUOTA',
  message: 'Used up.',
} as GuideResponse['notices'][number];

describe('useGuideStream', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    clearApiCache();
  });

  it('going back to an answered question shows its answer without asking again', async () => {
    const answerA = answer('OK');
    const ask = vi
      .spyOn(api, 'askGuideStream')
      .mockResolvedValueOnce(answerA)
      .mockImplementation(pending);
    const { result } = renderStream('/?ask=question+A');
    await waitFor(() => expect(result.current.stream.answer).toBe(answerA));

    act(() => void result.current.navigate('/?ask=question+B'));
    await waitFor(() => expect(result.current.stream.busy).toBe(true));
    act(() => void result.current.navigate(-1));

    expect(result.current.stream).toMatchObject({ answer: answerA, error: null, busy: false });
    expect(ask).toHaveBeenCalledTimes(2);
  });

  it('going back to a question that never finished asks it again from a clean state', async () => {
    const ask = vi.spyOn(api, 'askGuideStream').mockImplementation(pending);
    const { result } = renderStream('/?ask=question+A');
    await waitFor(() => expect(ask).toHaveBeenCalledTimes(1));

    act(() => void result.current.navigate('/?ask=question+B'));
    act(() => void result.current.navigate(-1));

    await waitFor(() => expect(ask).toHaveBeenCalledTimes(3));
    expect(result.current.stream).toMatchObject({ answer: null, busy: true, steps: [] });
  });

  it('sends the categories and the audience path as they are when the question is asked', async () => {
    const ask = vi.spyOn(api, 'askGuideStream').mockImplementation(pending);
    renderStream('/?ask=question+A&category=coding&path=BUILD_WITH_AI');

    await waitFor(() => expect(ask).toHaveBeenCalledTimes(1));
    expect(ask.mock.calls[0]?.[0]).toEqual({
      request: 'question A',
      path: 'BUILD_WITH_AI',
      categories: ['coding'],
    });
  });

  it('a filter changed after the question does not ask the guide again', async () => {
    const answerA = answer('OK');
    const ask = vi.spyOn(api, 'askGuideStream').mockResolvedValue(answerA);
    const { result } = renderStream('/?ask=question+A&category=coding');
    await waitFor(() => expect(result.current.stream.answer).toBe(answerA));

    act(
      () =>
        void result.current.navigate('/?ask=question+A&category=coding&category=agents&type=MODEL'),
    );

    expect(result.current.stream).toMatchObject({ answer: answerA, busy: false });
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('coming back to a page whose filters changed after the question keeps its answer', async () => {
    const answerA = answer('OK');
    const ask = vi.spyOn(api, 'askGuideStream').mockResolvedValue(answerA);
    const first = renderStream('/?ask=question+A&category=coding');
    await waitFor(() => expect(first.result.current.stream.answer).toBe(answerA));
    act(() => void first.result.current.navigate('/?ask=question+A&category=agents'));
    first.unmount();

    const { result } = renderStream('/?ask=question+A&category=agents');

    expect(result.current.stream).toMatchObject({ answer: answerA, busy: false });
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('asking again after a filter change asks with the filters of now', async () => {
    const ask = vi.spyOn(api, 'askGuideStream').mockResolvedValue(answer('OK'));
    const { result } = renderStream('/?ask=question+A&category=coding');
    await waitFor(() => expect(result.current.stream.busy).toBe(false));
    act(() => void result.current.navigate('/?ask=question+A&category=agents'));
    expect(ask).toHaveBeenCalledTimes(1);

    act(
      () =>
        void result.current.navigate('/?ask=question+A&category=agents', {
          state: { askedAt: 1 },
        }),
    );

    await waitFor(() => expect(ask).toHaveBeenCalledTimes(2));
    expect(ask.mock.calls[1]?.[0]).toEqual({ request: 'question A', categories: ['agents'] });
  });

  it.each([
    ['a partial answer', answer('PARTIAL'), 2],
    ['a provider failure', answer('LLM_UNAVAILABLE'), 2],
    ['the daily quota used up', answer('LLM_UNAVAILABLE', [QUOTA]), 1],
    ["the guide's model budget used up", answer('LLM_UNAVAILABLE', [BUDGET_SPENT]), 2],
    ['a question back', answer('NEEDS_CLARIFICATION'), 1],
    ['no candidates', answer('NO_CANDIDATES'), 1],
  ])('coming back to %s asks again only when it may change', async (_, reply, calls) => {
    const ask = vi.spyOn(api, 'askGuideStream').mockResolvedValue(reply);
    const first = renderStream('/?ask=question+A');
    await waitFor(() => expect(first.result.current.stream.answer).toBe(reply));
    first.unmount();

    renderStream('/?ask=question+A');

    await waitFor(() => expect(ask).toHaveBeenCalledTimes(calls));
  });
});
