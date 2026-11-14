import { render } from '@testing-library/react';
import { useRef } from 'react';
import { useStickyEdge } from './useStickyEdge';

const HEIGHTS: Record<string, number> = { header: 74, box: 230, examples: 145, consent: 177 };

function Bar({
  name,
  sticks = true,
  edge = 'top' as const,
}: {
  name: string;
  sticks?: boolean;
  edge?: 'top' | 'bottom';
}) {
  const bar = useRef<HTMLDivElement>(null);
  useStickyEdge(edge, bar, { sticks });
  return <div ref={bar} data-name={name} />;
}

function PinnedBox({ stuck }: { stuck: boolean }) {
  const bar = useRef<HTMLDivElement>(null);
  const more = useRef<HTMLDivElement>(null);
  useStickyEdge('top', bar, { sticks: true, foldsAway: stuck ? null : more });
  return (
    <div ref={bar} data-name="box">
      <div ref={more} data-name="examples" />
    </div>
  );
}

const padding = (edge: 'top' | 'bottom') =>
  document.documentElement.style.getPropertyValue(`--sticky-${edge}`);

beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: HTMLElement,
  ) {
    return { height: HEIGHTS[this.dataset.name ?? ''] ?? 0 } as DOMRect;
  });
});

describe('Feature: the page knows how much of it is covered by what sticks', () => {
  it('Scenario: the sticky header gives its height to --sticky-top, and takes it back when it goes', () => {
    const { unmount } = render(<Bar name="header" />);
    expect(padding('top')).toBe('74px');
    unmount();
    expect(padding('top')).toBe('0px');
  });

  it('Scenario: on the home page the header and the pinned box add up; the part that folds away does not count', () => {
    const { rerender } = render(
      <>
        <Bar name="header" />
        <PinnedBox stuck={false} />
      </>,
    );
    expect(padding('top')).toBe(`${74 + 230 - 145}px`);
    rerender(
      <>
        <Bar name="header" />
        <PinnedBox stuck />
      </>,
    );
    expect(padding('top')).toBe(`${74 + 230}px`);
  });

  it('Scenario: the consent bar gives its height to --sticky-bottom', () => {
    render(<Bar name="consent" edge="bottom" />);
    expect(padding('bottom')).toBe('177px');
    expect(padding('top')).toBe('0px');
  });

  it('Scenario: on a short screen nothing sticks, so nothing is kept clear', () => {
    render(<Bar name="header" sticks={false} />);
    expect(padding('top')).toBe('0px');
  });
});
