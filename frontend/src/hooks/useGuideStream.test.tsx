import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useNavigate } from 'react-router';
import { api } from '@/api/client';
import type { GuideResponse } from '@/api/types';
import { useGuideStream } from './useGuideStream';

const wrapper = ({ children }: { children: ReactNode }) => (
  <MemoryRouter initialEntries={['/?ask=question+A']}>{children}</MemoryRouter>
);

describe('useGuideStream', () => {
  afterEach(() => vi.restoreAllMocks());

  it('going back to an answered question asks again from a clean state', async () => {
    const answerA = { answer: 'A' } as unknown as GuideResponse;
    const pending = () => new Promise<GuideResponse>(() => {});
    const ask = vi.spyOn(api, 'askGuideStream')
      .mockResolvedValueOnce(answerA)
      .mockImplementation(pending);

    const { result } = renderHook(() => ({ stream: useGuideStream(), navigate: useNavigate() }), { wrapper });
    await waitFor(() => expect(result.current.stream.answer).toBe(answerA));

    act(() => void result.current.navigate('/?ask=question+B'));
    act(() => void result.current.navigate(-1));

    await waitFor(() => expect(ask).toHaveBeenCalledTimes(3));
    expect(result.current.stream.question).toBe('question A');
    expect(result.current.stream).toMatchObject({ answer: null, error: null, busy: true, steps: [] });
  });
});
