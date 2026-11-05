import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';
import { responseTimeLabel } from '@/lib/sourceHealth';
import { newThisWeek } from '@/lib/v4Fields';
import { beamAngle, contactAt, LEAVE_MS } from './radarClock';
import {
  angleOf,
  placeDots,
  radarFrame,
  type DotTone,
  type RadarDot,
  type RadarFrame,
  type Rect,
} from './radarLayout';
import { delayAfter, EMPTY, step, stillSet } from './radarRotation';
import { useBoxFocused, useRadarStill } from './useRadarMotion';

/** Before the first measure (and in tests): a wide hero. */
const FALLBACK = { width: 1280, height: 400 };

interface Size {
  width: number;
  height: number;
  title: Rect | null;
}

/** Where the text of an element is: its lines, not its box (a title box is wider than its balanced lines). */
function textBounds(el: Element): DOMRect {
  if (typeof document.createRange !== 'function') return el.getBoundingClientRect();
  const range = document.createRange();
  range.selectNodeContents(el);
  return typeof range.getBoundingClientRect === 'function'
    ? range.getBoundingClientRect()
    : el.getBoundingClientRect();
}

/** The title block (title, lede) as one rectangle in the radar's own coordinates. */
function titleRect(root: HTMLElement, title: HTMLElement | null): Rect | null {
  if (!title) return null;
  const base = root.getBoundingClientRect();
  const parts = [...title.children].map(textBounds).filter((r) => r.width);
  if (parts.length === 0) return null;
  return {
    left: Math.min(...parts.map((r) => r.left)) - base.left,
    top: Math.min(...parts.map((r) => r.top)) - base.top,
    right: Math.max(...parts.map((r) => r.right)) - base.left,
    bottom: Math.max(...parts.map((r) => r.bottom)) - base.top,
  };
}

/** The radar's box and the title inside it, measured again when either changes size. */
function useSize(root: HTMLElement | null, title: RefObject<HTMLElement | null>): Size {
  const [size, setSize] = useState<Size>({ ...FALLBACK, title: null });
  useLayoutEffect(() => {
    if (!root) return undefined;
    const measure = () => {
      const width = root.clientWidth || FALLBACK.width;
      const height = root.clientHeight || FALLBACK.height;
      const t = titleRect(root, title.current);
      setSize((s) =>
        s.width === width && s.height === height && JSON.stringify(s.title) === JSON.stringify(t)
          ? s
          : { width, height, title: t },
      );
    };
    measure();
    if (typeof ResizeObserver !== 'function') return undefined;
    const ro = new ResizeObserver(measure);
    ro.observe(root);
    if (title.current) ro.observe(title.current);
    return () => ro.disconnect();
  }, [root, title]);
  return size;
}

/** What a screen reader and the tooltip say: "GitHub, live, answered in 410 ms, 12 new this week". */
function useDotName() {
  const { t, i18n } = useTranslation();
  const ago = new Intl.RelativeTimeFormat(i18n.language, { numeric: 'auto' });
  return (s: SourceInfo, tone: DotTone, now: number): string => {
    const where = t(s.mode === 'LIVE' ? 'radar.dot.live' : 'radar.dot.index');
    const parts = [s.shortName, where];
    if (tone === 'failed') parts.push(t('radar.dot.failed'));
    else if (s.lastOutcome === 'TIMED_OUT') parts.push(t('radar.dot.timedOut'));
    else if (s.lastOutcome === 'RATE_LIMITED') parts.push(t('radar.dot.rateLimited'));
    else if (s.lastOutcome === 'SKIPPED') parts.push(t('radar.dot.skipped'));
    else if (!s.lastOutcome) parts.push(t('radar.dot.notChecked'));
    else if (s.mode === 'LIVE' && s.responseTimeMs != null)
      parts.push(
        t('radar.dot.answeredIn', {
          time: responseTimeLabel(s.responseTimeMs, i18n.language).short,
        }),
      );
    else if (s.mode !== 'LIVE' && s.lastCheckedAt) {
      const minutes = Math.max(0, Math.round((now - Date.parse(s.lastCheckedAt)) / 60_000));
      const when =
        minutes < 60
          ? ago.format(-minutes, 'minute')
          : ago.format(-Math.round(minutes / 60), 'hour');
      parts.push(t('radar.dot.checked', { when }));
    }
    const added = newThisWeek(s);
    if (added) parts.push(t('radar.dot.newThisWeek', { count: added }));
    return parts.join(', ');
  };
}

/** The art behind the dots: full rings around the title, and the beam (a sweep wedge) that the clock turns. */
function RadarArt({ f, beam }: { f: RadarFrame; beam: RefObject<SVGGElement | null> }) {
  const { cx, cy } = f;
  const outer = f.rings[f.rings.length - 1]! + 30;
  return (
    <svg
      className="v4-radar__art"
      width={f.width}
      height={f.height}
      aria-hidden="true"
      focusable="false"
    >
      <g className="v4-radar__rings">
        {f.rings.map((r) => (
          <circle key={r} cx={cx} cy={cy} r={r} />
        ))}
      </g>
      <g ref={beam} className="v4-radar__beam" style={{ transformOrigin: `${cx}px ${cy}px` }}>
        <path
          d={`M${cx} ${cy} L${cx + outer} ${cy} A${outer} ${outer} 0 0 0 ${cx + outer * Math.SQRT1_2} ${cy - outer * Math.SQRT1_2} Z`}
        />
      </g>
    </svg>
  );
}

/**
 * The radar on Home (ADR 0042, decision 4), like a navigation radar: full rings around the title and a beam that turns
 * once every 5 s. Only external sources, each in its own fixed spot, 3 to 5 at a time. A dot is painted when the beam
 * passes it (radarClock.ts); one source comes or goes every 1.6 s (radarRotation.ts). The beam and the dots run on
 * one clock, which stops while the search box has focus. Under reduced motion, or when a signed-in user turned the
 * motion off, the beam stands still and a fixed set of 4 shows at full opacity. Each dot links to the Sources page
 * and says its state in words. Up to 900 px only the rings and the beam show.
 */
export function HomeRadar({
  sources,
  box,
  title,
  random = Math.random,
}: {
  sources: SourceInfo[];
  box: RefObject<HTMLElement | null>;
  title: RefObject<HTMLElement | null>;
  random?: () => number;
}) {
  const [root, setRoot] = useState<HTMLDivElement | null>(null);
  const beam = useRef<SVGGElement>(null);
  const size = useSize(root, title);
  const frame = radarFrame(size.width, size.height, size.title);
  const dots = placeDots(sources, frame);
  const still = useRadarStill();
  const paused = useBoxFocused(box);
  const state = still ? ' is-still' : paused ? ' is-paused' : '';
  return (
    <div ref={setRoot} className={`v4-radar${state}`}>
      <RadarArt f={frame} beam={beam} />
      <RadarDots
        key={`${dots.map((d) => d.source.id).join(' ')}${still ? ' still' : ''}`}
        dots={dots}
        frame={frame}
        beam={beam}
        running={!still && !paused}
        still={still}
        random={random}
      />
    </div>
  );
}

/** Frames longer than this (a tab in the background) count as this long, so nothing jumps on return. */
const MAX_FRAME_MS = 100;

/** The radar's clock: `onFrame(t)` on each animation frame while `running`, with `t` the ms it has run so far. */
function useClock(running: boolean, onFrame: (t: number) => void) {
  const clock = useRef(0);
  const latest = useRef(onFrame);
  useLayoutEffect(() => {
    latest.current = onFrame;
  });
  useEffect(() => {
    if (!running) return undefined;
    let last: number | null = null;
    let id = 0;
    const tick = (now: number) => {
      if (last !== null) clock.current += Math.min(MAX_FRAME_MS, Math.max(0, now - last));
      last = now;
      latest.current(clock.current);
      id = requestAnimationFrame(tick);
    };
    id = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(id);
  }, [running]);
}

interface Life {
  addedAt: number;
  leftAt: number | null;
}

interface View {
  /** On the radar now (screen readers too). */
  shown: string[];
  /** Fading out, hidden from screen readers. */
  leaving: string[];
}

/**
 * Every dot is in the page in its own spot. On each frame the clock turns the beam and sets each dot's `--o`, `--pop`
 * and `--glow` (home.css); the React state changes only when a source comes or goes. Standing still: a fixed random set
 * of 4 at full opacity, no clock. Mounted again (a React key) when the sources or `still` change.
 */
function RadarDots({
  dots,
  frame,
  beam,
  running,
  still,
  random,
}: {
  dots: RadarDot[];
  frame: RadarFrame;
  beam: RefObject<SVGGElement | null>;
  running: boolean;
  still: boolean;
  random: () => number;
}) {
  const { t } = useTranslation();
  const name = useDotName();
  const [now] = useState(Date.now);
  const [ids] = useState(() => dots.map((d) => d.source.id));
  const [view, setView] = useState<View>(() => ({
    shown: still ? stillSet(ids, random) : [],
    leaving: [],
  }));
  const els = useRef(new Map<string, HTMLElement>());
  const cycle = useRef(EMPTY);
  const nextStep = useRef(delayAfter(EMPTY, ids.length));
  const lives = useRef(new Map<string, Life>());
  const angles = new Map(dots.map((d) => [d.source.id, angleOf(d, frame)]));

  useClock(running, (time) => {
    let changed = false;
    while (time >= nextStep.current) {
      const next = step(cycle.current, ids, random);
      for (const id of next.shown)
        if (!cycle.current.shown.includes(id))
          lives.current.set(id, { addedAt: time, leftAt: null });
      for (const id of cycle.current.shown)
        if (!next.shown.includes(id)) lives.current.get(id)!.leftAt = time;
      changed ||= next !== cycle.current;
      cycle.current = next;
      nextStep.current += delayAfter(next, ids.length);
    }
    for (const [id, life] of lives.current)
      if (life.leftAt !== null && time - life.leftAt >= LEAVE_MS) {
        lives.current.delete(id);
        els.current.get(id)?.style.setProperty('--o', '0');
        changed = true;
      }
    if (changed)
      setView({
        shown: [...cycle.current.shown],
        leaving: [...lives.current].filter(([, l]) => l.leftAt !== null).map(([id]) => id),
      });
    beam.current?.style.setProperty('transform', `rotate(${beamAngle(time).toFixed(2)}deg)`);
    for (const [id, life] of lives.current) {
      const c = contactAt(angles.get(id) ?? 0, time, life.addedAt, life.leftAt);
      const el = els.current.get(id);
      el?.style.setProperty('--o', c.opacity.toFixed(3));
      el?.style.setProperty('--pop', c.pop.toFixed(3));
      el?.style.setProperty('--glow', c.glow.toFixed(3));
    }
  });

  return (
    <ul className="v4-radar__dots" aria-label={t('radar.label')}>
      {dots.map((d) => {
        const id = d.source.id;
        const on = view.shown.includes(id);
        const leaving = view.leaving.includes(id);
        const label = name(d.source, d.tone, now);
        return (
          <li
            key={id}
            className={on ? 'is-on' : leaving ? 'is-leaving' : undefined}
            style={{ left: d.x, top: d.y - d.r }}
            aria-hidden={on ? undefined : true}
            inert={!on}
          >
            <Link
              ref={(el) => {
                if (el) els.current.set(id, el);
                else els.current.delete(id);
              }}
              to="/sources"
              className={`v4-dot v4-dot--${d.tone}`}
              aria-label={label}
              title={label}
              data-source={id}
              style={{ transformOrigin: `50% ${d.r}px` }}
            >
              <span className="v4-dot__mark" style={{ width: d.r * 2, height: d.r * 2 }} />
              <span className="v4-dot__name">{d.source.shortName}</span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
