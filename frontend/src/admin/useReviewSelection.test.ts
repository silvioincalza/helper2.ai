import { act, renderHook } from '@testing-library/react';
import type { ReviewItem } from '@/api/types';
import { openPicks, picksAfterBulk, togglePick, useReviewSelection } from './useReviewSelection';

const item = (n: number, over: Partial<ReviewItem> = {}): ReviewItem => ({
  id: `item-${n}`,
  kind: 'ENRICHMENT_VALIDATION',
  status: 'OPEN',
  reason: `Item ${n}`,
  createdAt: '2026-11-01T10:00:00Z',
  ...over,
});
const picks = (...items: ReviewItem[]) => new Map(items.map((i) => [i.id, i] as const));

describe('the picked review items', () => {
  it('picks an item and, asked again, lets it go, without changing the given picks', () => {
    const none = picks();

    const one = togglePick(none, item(1));
    const two = togglePick(one, item(2));

    expect([...two.keys()]).toEqual(['item-1', 'item-2']);
    expect([...togglePick(two, item(1)).keys()]).toEqual(['item-2']);
    expect(none.size).toBe(0);
  });

  it('keeps only the picks that are still open on the loaded page', () => {
    const picked = picks(item(1), item(2), item(3));

    const kept = openPicks(picked, [item(1), item(2, { status: 'ACCEPTED' }), item(4)]);

    expect([...kept.keys()]).toEqual(['item-1']);
  });

  it('hands back the same picks when every one is still open', () => {
    const picked = picks(item(1), item(2));

    expect(openPicks(picked, [item(2), item(1), item(3)])).toBe(picked);
  });

  it('after a bulk decision keeps the refused items and the ones that were not sent', () => {
    const picked = picks(item(1), item(2), item(3));

    const kept = picksAfterBulk(picked, ['item-1', 'item-2'], new Set(['item-2']));

    expect([...kept.keys()]).toEqual(['item-2', 'item-3']);
  });
});

describe('useReviewSelection', () => {
  it('never picks an item that is already decided', () => {
    const page = [item(1), item(2, { status: 'REJECTED' })];
    const { result } = renderHook(() => useReviewSelection(page));

    act(() => result.current.toggle(page[1]!));
    act(() => result.current.toggle(page[0]!));

    expect([...result.current.picked.keys()]).toEqual(['item-1']);
  });

  it('keeps the picks while the page loads, drops the decided ones when it arrives, and clears', () => {
    const { result, rerender } = renderHook(
      ({ page }: { page: ReviewItem[] | undefined }) => useReviewSelection(page),
      { initialProps: { page: [item(1), item(2)] as ReviewItem[] | undefined } },
    );
    act(() => result.current.toggle(item(1)));
    act(() => result.current.toggle(item(2)));

    rerender({ page: undefined });
    expect(result.current.picked.size).toBe(2);

    rerender({ page: [item(2)] });
    expect([...result.current.picked.keys()]).toEqual(['item-2']);

    act(() => result.current.clear());
    expect(result.current.picked.size).toBe(0);
  });

  it('after a bulk decision keeps the refused items picked', () => {
    const page = [item(1), item(2)];
    const { result } = renderHook(() => useReviewSelection(page));
    act(() => result.current.toggle(page[0]!));
    act(() => result.current.toggle(page[1]!));

    act(() => result.current.keepFailed(['item-1', 'item-2'], new Set(['item-1'])));

    expect([...result.current.picked.keys()]).toEqual(['item-1']);
  });
});
