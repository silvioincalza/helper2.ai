import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';
import { checkedSecondsAgo } from '@/lib/sourceAge';
import { relativeTime } from '@/lib/format';
import { responseTimeLabel } from '@/lib/sourceHealth';
import { newThisWeek } from '@/lib/sourceCounts';
import { beamAngle, contactAt, LEAVE_MS } from './radarClock';
import {
  angleOf,
  placeDot,
  placeDots,
  radarGroup,
  radarFrame,
  PHONE_MAX,
  type DotTone,
  type RadarDot,
  type RadarFrame,
  type Rect,
} from './radarLayout';
import { delayAfter, EMPTY, mostFor, step, stillSet } from './radarRotation';
import { useMouseOver, useRadarStill } from './useRadarMotion';

const FALLBACK = { width: 1280, height: 400 };

interface Size {
  width: number;
  height: number;
  title: Rect[] | null;
}

function lineBoxes(el: Element): DOMRect[] {
  const walk = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  const boxes: DOMRect[] = [];
  for (let node = walk.nextNode(); node; node = walk.nextNode()) {
    const range = document.createRange();
    range.selectNodeContents(node);
    boxes.push(...range.getClientRects());
  }
  return boxes;
}

function textBoxes(el: Element, perLine: boolean): DOMRect[] {
  if (typeof document.createRange !== 'function') return [el.getBoundingClientRect()];
  const range = document.createRange();
  range.selectNodeContents(el);
  if (perLine && typeof range.getClientRects === 'function') return lineBoxes(el);
  return [
    typeof range.getBoundingClientRect === 'function'
      ? range.getBoundingClientRect()
      : el.getBoundingClientRect(),
  ];
}

function titleRects(root: HTMLElement, title: HTMLElement | null): Rect[] | null {
  if (!title) return null;
  const base = root.getBoundingClientRect();
  const perLine = root.clientWidth <= PHONE_MAX;
  const parts = [...title.children].flatMap((c) => textBoxes(c, perLine)).filter((r) => r.width);
  if (parts.length === 0) return null;
  const shift = (r: DOMRect): Rect => ({
    left: r.left - base.left,
    top: r.top - base.top,
    right: r.right - base.left,
    bottom: r.bottom - base.top,
  });
  if (perLine) return parts.map(shift);
  return [
    {
      left: Math.min(...parts.map((r) => r.left)) - base.left,
      top: Math.min(...parts.map((r) => r.top)) - base.top,
      right: Math.max(...parts.map((r) => r.right)) - base.left,
      bottom: Math.max(...parts.map((r) => r.bottom)) - base.top,
    },
  ];
}

function useSize(root: HTMLElement | null, title: RefObject<HTMLElement | null>): Size {
  const [size, setSize] = useState<Size>({ ...FALLBACK, title: null });
  useLayoutEffect(() => {
    if (!root) return undefined;
    const measure = () => {
      const width = root.clientWidth || FALLBACK.width;
      const height = root.clientHeight || FALLBACK.height;
      const t = titleRects(root, title.current);
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

function useDotName() {
  const { t, i18n } = useTranslation();
  const ago = relativeTime();
  return (s: SourceInfo, tone: DotTone): string => {
    const where = t(s.mode === 'LIVE' ? 'radar.dot.live' : 'radar.dot.index');
    const parts = [s.shortName, where];
    const checked = checkedSecondsAgo(s);
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
    else if (s.mode !== 'LIVE' && checked != null) {
      const minutes = Math.round(checked / 60);
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

const TRAIL = [0.3, 0.22, 0.16, 0.11, 0.08, 0.05, 0.035, 0.02];
const TRAIL_STEP = 7;
function stepsOn([from, to]: [number, number], step: number, ends: boolean): number[] {
  const out: number[] = [];
  for (let d = from; d <= to; d += step) if (ends || (d !== from && d !== to)) out.push(d);
  return out;
}

function slice(cx: number, cy: number, r: number, i: number): string {
  const from = (-i * TRAIL_STEP * Math.PI) / 180;
  const to = (-(i + 1) * TRAIL_STEP * Math.PI) / 180;
  const p = (a: number) =>
    `${(cx + Math.cos(a) * r).toFixed(1)} ${(cy + Math.sin(a) * r).toFixed(1)}`;
  return `M${cx} ${cy} L${p(from)} A${r} ${r} 0 0 0 ${p(to)} Z`;
}

function RadarArt({ f, beam }: { f: RadarFrame; beam: RefObject<SVGGElement | null> }) {
  const { cx, cy } = f;
  const ring = f.rings[f.rings.length - 1]!;
  const outer = ring + 30;
  return (
    <svg
      className="h2-radar__art"
      width={f.width}
      height={f.height}
      aria-hidden="true"
      focusable="false"
    >
      <g className="h2-radar__rings">
        {f.rings.map((r) => (
          <circle key={r} cx={cx} cy={cy} r={r} />
        ))}
        {stepsOn(f.arc, 5, true).map((deg) => {
          const a = (deg * Math.PI) / 180;
          const inner = ring - (deg % 30 === 0 ? 10 : 5);
          return (
            <line
              key={`tick-${deg}`}
              className="h2-radar__tick"
              x1={cx + Math.cos(a) * inner}
              y1={cy - Math.sin(a) * inner}
              x2={cx + Math.cos(a) * ring}
              y2={cy - Math.sin(a) * ring}
            />
          );
        })}
        {stepsOn(f.arc, 30, false).map((deg) => {
          const a = (deg * Math.PI) / 180;
          return (
            <line
              key={deg}
              x1={cx}
              y1={cy}
              x2={cx + Math.cos(a) * outer}
              y2={cy - Math.sin(a) * outer}
            />
          );
        })}
      </g>
      <g ref={beam} className="h2-radar__beam" style={{ transformOrigin: `${cx}px ${cy}px` }}>
        {TRAIL.map((opacity, i) => (
          <path key={i} d={slice(cx, cy, outer, i)} fillOpacity={opacity} />
        ))}
        <line className="h2-radar__halo" x1={cx} y1={cy} x2={cx + outer} y2={cy} />
        <line x1={cx} y1={cy} x2={cx + outer} y2={cy} />
      </g>
      <g className="h2-radar__origin">
        <circle cx={cx} cy={cy} r={10} />
        <circle cx={cx} cy={cy} r={3.5} />
      </g>
    </svg>
  );
}

function useFocusWithin(el: HTMLElement | null): boolean {
  const [seen, setSeen] = useState<{ el: HTMLElement; within: boolean } | null>(null);
  useEffect(() => {
    if (!el) return undefined;
    const enter = () => setSeen({ el, within: true });
    const leave = (e: FocusEvent) =>
      setSeen({ el, within: el.contains(e.relatedTarget as Node | null) });
    el.addEventListener('focusin', enter);
    el.addEventListener('focusout', leave);
    return () => {
      el.removeEventListener('focusin', enter);
      el.removeEventListener('focusout', leave);
    };
  }, [el]);
  return seen?.el === el && seen.within;
}

export function HomeRadar({
  sources,
  title,
  dots,
  random = Math.random,
}: {
  sources: SourceInfo[];
  title: RefObject<HTMLElement | null>;
  dots: HTMLElement | null;
  random?: () => number;
}) {
  const [root, setRoot] = useState<HTMLDivElement | null>(null);
  const beam = useRef<SVGGElement>(null);
  const size = useSize(root, title);
  const frame = radarFrame(size.width, size.height, size.title);
  const still = useRadarStill();
  const overHero = useMouseOver(root?.parentElement ?? null);
  const overDots = useMouseOver(dots);
  const focusOnDot = useFocusWithin(dots);
  const paused = overHero || overDots || focusOnDot;
  const state = still ? ' is-still' : paused ? ' is-paused' : '';
  const key = [
    sources.map((s) => s.id).join(' '),
    still ? 'still' : '',
    JSON.stringify([frame.width, frame.height, frame.exclusions]),
  ].join('|');
  return (
    <div ref={setRoot} className={`h2-radar${state}`}>
      <RadarArt f={frame} beam={beam} />
      {dots &&
        createPortal(
          <RadarDots
            key={key}
            sources={sources}
            frame={frame}
            beam={beam}
            cap={mostFor(frame.width)}
            running={!still && !paused}
            still={still}
            random={random}
          />,
          dots,
        )}
    </div>
  );
}

const MAX_FRAME_MS = 100;

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
  shown: string[];
  leaving: string[];
  spots: Map<string, RadarDot>;
}

function RadarDots({
  sources,
  frame,
  beam,
  cap,
  running,
  still,
  random,
}: {
  sources: SourceInfo[];
  frame: RadarFrame;
  beam: RefObject<SVGGElement | null>;
  cap: number;
  running: boolean;
  still: boolean;
  random: () => number;
}) {
  const { t } = useTranslation();
  const name = useDotName();
  const [byId] = useState(() => new Map(sources.map((s) => [s.id, s])));
  const [ids] = useState(() => sources.map((s) => s.id));
  const groupOf = (id: string) => radarGroup(byId.get(id)!);
  const [first] = useState<View>(() => {
    const shown = still ? stillSet(ids, random, cap) : [];
    const placed = placeDots(
      shown.map((id) => byId.get(id)!),
      frame,
    );
    return { shown, leaving: [], spots: new Map(placed.map((d) => [d.source.id, d])) };
  });
  const [view, setView] = useState<View>(first);
  const spots = useRef(new Map(first.spots));
  const els = useRef(new Map<string, HTMLElement>());
  const cycle = useRef(EMPTY);
  const nextStep = useRef(delayAfter(EMPTY, ids.length, cap));
  const lives = useRef(new Map<string, Life>());

  const arrive = (id: string) => {
    const onRadar = [...spots.current.values()];
    spots.current.set(id, placeDot(byId.get(id)!, frame, onRadar));
  };

  useClock(running, (time) => {
    let changed = false;
    while (time >= nextStep.current) {
      const next = step(cycle.current, ids, random, groupOf, cap);
      for (const id of next.shown)
        if (!cycle.current.shown.includes(id)) {
          if (lives.current.has(id)) spots.current.delete(id);
          arrive(id);
          lives.current.set(id, { addedAt: time, leftAt: null });
        }
      for (const id of cycle.current.shown)
        if (!next.shown.includes(id)) lives.current.get(id)!.leftAt = time;
      changed ||= next !== cycle.current;
      cycle.current = next;
      nextStep.current += delayAfter(next, ids.length, cap);
    }
    for (const [id, life] of lives.current)
      if (life.leftAt !== null && time - life.leftAt >= LEAVE_MS) {
        lives.current.delete(id);
        spots.current.delete(id);
        els.current.get(id)?.style.setProperty('--o', '0');
        changed = true;
      }
    if (changed)
      setView({
        shown: [...cycle.current.shown],
        leaving: [...lives.current].filter(([, l]) => l.leftAt !== null).map(([id]) => id),
        spots: new Map(spots.current),
      });
    beam.current?.style.setProperty('transform', `rotate(${beamAngle(time).toFixed(2)}deg)`);
    for (const [id, life] of lives.current) {
      const spot = spots.current.get(id);
      const c = contactAt(spot ? angleOf(spot, frame) : 0, time, life.addedAt, life.leftAt);
      const el = els.current.get(id);
      el?.style.setProperty('--o', c.opacity.toFixed(3));
      el?.style.setProperty('--pop', c.pop.toFixed(3));
      el?.style.setProperty('--glow', c.glow.toFixed(3));
      el?.style.setProperty('--ping', c.ping.toFixed(3));
    }
  });

  const onRadar = [...view.shown, ...view.leaving.filter((id) => !view.shown.includes(id))];
  return (
    <ul
      className={['h2-radar__dots', still && 'is-still', !frame.names && 'is-nameless']
        .filter(Boolean)
        .join(' ')}
      style={{ height: frame.height }}
      aria-label={t('radar.label')}
    >
      {onRadar.map((id) => {
        const d = view.spots.get(id);
        if (!d) return null;
        const on = view.shown.includes(id);
        const label = name(d.source, d.tone);
        return (
          <li
            key={id}
            className={on ? 'is-on' : 'is-leaving'}
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
              className={`h2-dot h2-dot--${d.tone}`}
              aria-label={label}
              title={label}
              data-source={id}
              style={{ transformOrigin: `50% ${d.r}px` }}
            >
              <span className="h2-dot__mark" style={{ width: d.r * 2, height: d.r * 2 }} />
              <span className="h2-dot__name">{d.source.shortName}</span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
