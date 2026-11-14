import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { useRef, useState } from 'react';
import { MemoryRouter } from 'react-router';
import type { Session } from '@/context/MeContext';
import { radarSources } from '@/lib/sourceCounts';
import { axeViolations } from '@/test/a11y';
import { ANON, STAGING_SOURCES, USER } from '@/test/fixtures';
import { HomeRadar } from './HomeRadar';
import { TURN_MS } from './radarClock';
import { INTRO_MS, STEP_MS } from './radarRotation';

const session: { me: Session } = { me: ANON };
vi.mock('@/context/MeContext', () => ({
  useMe: () => ({ me: session.me, loaded: true, isAdmin: false, refresh: () => undefined }),
}));

const external = radarSources(STAGING_SOURCES);

function seeded(seed = 7): () => number {
  let x = seed;
  return () => {
    x = (x * 1103515245 + 12345) % 2147483648;
    return x / 2147483648;
  };
}

function reducedMotion(on: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: on && query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

function Page({ sources = external }: { sources?: typeof external }) {
  const title = useRef<HTMLDivElement>(null);
  const [dots, setDots] = useState<HTMLDivElement | null>(null);
  return (
    <MemoryRouter>
      <section>
        <HomeRadar sources={sources} title={title} dots={dots} random={seeded()} />
        <div ref={title}>
          <h1>The metasearch</h1>
        </div>
      </section>
      <input aria-label="Search AI resources" />
      <div ref={setDots} />
    </MemoryRouter>
  );
}

const shown = () =>
  within(screen.getByRole('list', { name: 'Some of the sources we search' }))
    .queryAllByRole('link')
    .map((a) => a.dataset.source!);
const advance = (ms: number) => act(() => vi.advanceTimersByTime(ms));
const dot = (container: HTMLElement, id: string) =>
  container.querySelector<HTMLElement>(`[data-source="${id}"]`)!;
const opacity = (el: HTMLElement) => Number(el.style.getPropertyValue('--o') || 0);
const beam = (container: HTMLElement) =>
  container.querySelector<SVGGElement>('.h2-radar__beam')!.style.transform;
const radar = (container: HTMLElement) => container.querySelector('.h2-radar')!;

beforeEach(() => {
  session.me = ANON;
  reducedMotion(false);
  vi.useFakeTimers({
    toFake: [
      'setTimeout',
      'clearTimeout',
      'requestAnimationFrame',
      'cancelAnimationFrame',
      'Date',
      'performance',
    ],
  });
});
afterEach(() => {
  vi.useRealTimers();
});

describe('Feature: the radar works like a navigation radar (ADR 0040, decision 6)', () => {
  it('Scenario: it starts with 5 sources, one after another', () => {
    render(<Page />);
    expect(shown()).toHaveLength(0);
    advance(INTRO_MS + 50);
    for (let n = 1; n <= 5; n++) {
      expect(shown()).toHaveLength(n);
      advance(INTRO_MS);
    }
  });

  it('Scenario: the count always stays between 5 and 10', () => {
    render(<Page />);
    advance(5 * INTRO_MS + 50);
    const counts: number[] = [];
    for (let i = 0; i < 60; i++) {
      advance(STEP_MS / 2);
      counts.push(shown().length);
    }
    expect(Math.min(...counts)).toBe(5);
    expect(Math.max(...counts)).toBe(10);
  });

  it('Scenario: the oldest leaves first, and fades out where it was', () => {
    const { container } = render(<Page />);
    let order: string[] = [];
    let left = 0;
    for (let i = 0; i < 40; i++) {
      advance(i < 5 ? INTRO_MS : STEP_MS);
      const now = shown();
      const gone = order.filter((id) => !now.includes(id));
      if (gone.length > 0) {
        expect(gone).toEqual([order[0]]);
        expect(dot(container, gone[0]!).parentElement).toHaveClass('is-leaving');
        left++;
      }
      order = [
        ...order.filter((id) => now.includes(id)),
        ...now.filter((id) => !order.includes(id)),
      ];
    }
    expect(left).toBeGreaterThan(5);
  });

  it('Scenario: a contact is painted only when the beam passes it, then fades to its rest level', () => {
    const { container } = render(<Page />);
    advance(INTRO_MS + 50);
    const el = dot(container, shown()[0]!);
    let peak = 0;
    for (let ms = 0; ms < TURN_MS; ms += 16) {
      advance(16);
      peak = Math.max(peak, opacity(el));
    }
    expect(peak).toBeGreaterThan(0.95);
    expect(opacity(el)).toBeLessThan(peak);
    expect(opacity(el)).toBeGreaterThan(0.2);
  });

  it('Scenario: only external sources, never Helper2 itself', () => {
    render(<Page sources={radarSources(STAGING_SOURCES)} />);
    const seen = new Set<string>();
    for (let i = 0; i < 60; i++) {
      advance(i < 5 ? INTRO_MS : STEP_MS);
      shown().forEach((id) => seen.add(id));
    }
    const externalIds = STAGING_SOURCES.filter((s) => s.external).map((s) => s.id);
    for (const id of seen) expect(externalIds).toContain(id);
    expect(seen.size).toBeGreaterThan(5);
    for (const own of ['helper2-index', 'helper2-semantic', 'curated'])
      expect(seen).not.toContain(own);
  });

  it('Scenario: each dot shows only its short name, filled with its state colour', () => {
    const { container } = render(<Page />);
    advance(5 * INTRO_MS + 50);
    const link = within(screen.getByRole('list', { name: /sources we search/ })).getAllByRole(
      'link',
    )[0]!;
    const source = external.find((s) => s.id === link.dataset.source)!;
    expect(link).toHaveTextContent(new RegExp(`^${source.shortName}$`));
    expect(link.getAttribute('aria-label')).toMatch(new RegExp(`^${source.shortName}, `));
    expect(link).toHaveAttribute('title', link.getAttribute('aria-label'));
    expect(link).toHaveAttribute('href', '/sources');
    const tones = [...container.querySelectorAll('.h2-dot')].map((a) => a.className).join(' ');
    expect(tones).not.toMatch(/h2-dot--(live|index)/);
    expect(tones).toMatch(/h2-dot--answering/);
  });

  it("Scenario: a dot says how long ago its source was checked by the server's clock", () => {
    const checked = external.map((s) => ({
      ...s,
      lastOutcome: 'SUCCEEDED' as const,
      lastCheckedAt: '2000-01-01T00:00:00Z',
      lastCheckedSecondsAgo: 120,
    }));
    render(<Page sources={checked} />);
    const labels: string[] = [];
    for (let i = 0; i < 40; i++) {
      advance(i < 5 ? INTRO_MS : STEP_MS);
      within(screen.getByRole('list', { name: /sources we search/ }))
        .queryAllByRole('link')
        .filter((a) => checked.find((s) => s.id === a.dataset.source)?.mode !== 'LIVE')
        .forEach((a) => labels.push(a.getAttribute('aria-label') ?? ''));
    }
    expect(labels.length).toBeGreaterThan(0);
    for (const label of labels) expect(label).toMatch(/2 minutes ago/);
  });

  it('Scenario: only the dots on the radar are in the page; one fading out is hidden from screen readers', () => {
    const { container } = render(<Page />);
    advance(5 * INTRO_MS + 50);
    expect(container.querySelectorAll('.h2-radar__dots li')).toHaveLength(5);
    advance(6 * STEP_MS);
    const leaving = container.querySelectorAll('.h2-radar__dots li.is-leaving');
    expect(leaving.length).toBeGreaterThan(0);
    for (const li of leaving) {
      expect(li).toHaveAttribute('aria-hidden', 'true');
      expect(li).toHaveAttribute('inert');
    }
  });

  it('Scenario: with 47 sources the live ones, the collectors and the categories take turns', () => {
    const categories = Array.from({ length: 34 }, (_, i) => ({
      ...external.find((s) => s.mode !== 'LIVE')!,
      id: `discovery-c${i}`,
      shortName: `Category ${i}`.slice(0, 12),
      family: 'developers' as const,
    }));
    const many = [...external, ...categories];
    render(<Page sources={many} />);
    const seen = new Set<string>();
    const groups = { live: 0, index: 0, discovery: 0 };
    let before: string[] = [];
    for (let i = 0; i < 150; i++) {
      advance(i < 5 ? INTRO_MS : STEP_MS);
      const now = shown();
      for (const id of now.filter((x) => !before.includes(x))) {
        seen.add(id);
        const s = many.find((x) => x.id === id)!;
        groups[s.mode === 'LIVE' ? 'live' : s.family ? 'discovery' : 'index']++;
      }
      before = now;
    }
    expect(seen.size).toBeGreaterThan(external.length);
    const arrivals = Object.values(groups);
    expect(Math.max(...arrivals) - Math.min(...arrivals)).toBeLessThanOrEqual(
      Math.max(...arrivals) / 3,
    );
  });

  it('Scenario: while the mouse is over the radar the clock stops; when it leaves, it runs again', () => {
    const { container } = render(<Page />);
    advance(5 * INTRO_MS + 1000);
    const hero = container.querySelector('section')!;
    fireEvent.pointerEnter(hero, { pointerType: 'mouse' });
    expect(radar(container)).toHaveClass('is-paused');
    const angle = beam(container);
    const before = shown();
    advance(20_000);
    expect(beam(container)).toBe(angle);
    expect(shown()).toEqual(before);
    fireEvent.pointerLeave(hero, { pointerType: 'mouse' });
    expect(radar(container)).not.toHaveClass('is-paused');
    advance(STEP_MS + 100);
    expect(beam(container)).not.toBe(angle);
    expect(shown()).not.toEqual(before);
  });

  it('Scenario: a mouse already over the hero when the radar mounts holds it at once', () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn((query: string) => ({
        matches: query.includes('(hover: hover)'),
        media: query,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
      })),
    );
    const matches = Element.prototype.matches;
    vi.spyOn(Element.prototype, 'matches').mockImplementation(function (
      this: Element,
      selector: string,
    ) {
      return selector === ':hover' ? this.tagName === 'SECTION' : matches.call(this, selector);
    });
    const { container } = render(<Page />);
    expect(radar(container)).toHaveClass('is-paused');
    fireEvent.pointerLeave(container.querySelector('section')!, { pointerType: 'mouse' });
    expect(radar(container)).not.toHaveClass('is-paused');
    vi.mocked(Element.prototype.matches).mockRestore();
  });

  it('Scenario: on a touch screen a sticky hover at mount does not hold the radar', () => {
    const matches = Element.prototype.matches;
    vi.spyOn(Element.prototype, 'matches').mockImplementation(function (
      this: Element,
      selector: string,
    ) {
      return selector === ':hover' ? this.tagName === 'SECTION' : matches.call(this, selector);
    });
    const { container } = render(<Page />);
    expect(radar(container)).not.toHaveClass('is-paused');
    vi.mocked(Element.prototype.matches).mockRestore();
  });

  it('Scenario: typing in the search box does not stop the radar', () => {
    const { container } = render(<Page />);
    advance(5 * INTRO_MS + 1000);
    const box = screen.getByRole('textbox', { name: 'Search AI resources' });
    act(() => box.focus());
    expect(radar(container)).not.toHaveClass('is-paused');
    const angle = beam(container);
    advance(2000);
    expect(beam(container)).not.toBe(angle);
  });

  it('Scenario: a touch does not hold the radar', () => {
    const { container } = render(<Page />);
    fireEvent.pointerEnter(container.querySelector('section')!, { pointerType: 'touch' });
    expect(radar(container)).not.toHaveClass('is-paused');
  });

  it('Scenario: the dots come after the search box in the page, so Tab reaches the box first', () => {
    render(<Page />);
    advance(5 * INTRO_MS + 50);
    const box = screen.getByRole('textbox', { name: 'Search AI resources' });
    const dots = screen.getByRole('list', { name: 'Some of the sources we search' });
    expect(box.compareDocumentPosition(dots) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(dots.style.height).toBe('400px');
  });

  it('Scenario: while the focus is on a dot the clock stops, so the dot cannot leave; when the focus leaves, it runs again', () => {
    const { container } = render(<Page />);
    advance(5 * INTRO_MS + 1000);
    const link = within(
      screen.getByRole('list', { name: 'Some of the sources we search' }),
    ).getAllByRole('link')[0]!;
    act(() => link.focus());
    expect(radar(container)).toHaveClass('is-paused');
    const angle = beam(container);
    const before = shown();
    advance(60_000);
    expect(link).toHaveFocus();
    expect(beam(container)).toBe(angle);
    expect(shown()).toEqual(before);
    act(() => screen.getByRole('textbox', { name: 'Search AI resources' }).focus());
    expect(radar(container)).not.toHaveClass('is-paused');
    advance(STEP_MS + 100);
    expect(beam(container)).not.toBe(angle);
  });

  it('Scenario: while the mouse is over a dot the clock stops', () => {
    const { container } = render(<Page />);
    advance(5 * INTRO_MS + 1000);
    const dots = screen.getByRole('list', { name: 'Some of the sources we search' });
    fireEvent.pointerEnter(dots.parentElement!, { pointerType: 'mouse' });
    expect(radar(container)).toHaveClass('is-paused');
    fireEvent.pointerLeave(dots.parentElement!, { pointerType: 'mouse' });
    expect(radar(container)).not.toHaveClass('is-paused');
  });

  it('Scenario: reduced motion stays still when the mouse comes and goes', () => {
    reducedMotion(true);
    const { container } = render(<Page />);
    const hero = container.querySelector('section')!;
    fireEvent.pointerEnter(hero, { pointerType: 'mouse' });
    fireEvent.pointerLeave(hero, { pointerType: 'mouse' });
    expect(radar(container)).toHaveClass('is-still');
    expect(beam(container)).toBe('');
  });

  it('Scenario: under reduced motion the beam stands still and a fixed set of 7 shows', () => {
    reducedMotion(true);
    const { container } = render(<Page />);
    expect(radar(container)).toHaveClass('is-still');
    const before = shown();
    expect(before).toHaveLength(7);
    advance(60_000);
    expect(shown()).toEqual(before);
    expect(beam(container)).toBe('');
  });

  it('Scenario: a signed-in user who turned the motion off sees a fixed set of 7', () => {
    session.me = { ...USER, preferences: { radarMotion: false } };
    const { container } = render(<Page />);
    expect(radar(container)).toHaveClass('is-still');
    const before = shown();
    expect(before).toHaveLength(7);
    advance(60_000);
    expect(shown()).toEqual(before);
    expect(beam(container)).toBe('');
  });

  it('Scenario: the radar passes axe', async () => {
    reducedMotion(true);
    vi.useRealTimers();
    const { container } = render(<Page />);
    expect(await axeViolations(container)).toEqual([]);
  });
});
