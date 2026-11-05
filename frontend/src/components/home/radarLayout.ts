import type { SourceInfo } from '@/api/types';
import { newThisWeek } from '@/lib/v4Fields';

/**
 * Where the Home radar's dots go (ADR 0042). Full circles centred on the hero, the title in the middle. Every external
 * source gets a fixed slot from the order GET /sources lists it in, so a dot is in the same place on every visit; the
 * radar shows a few of them at a time. Pure functions: the component measures, this module places.
 */

export type DotTone = 'answering' | 'warn' | 'failed' | 'idle';

export interface Rect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** The drawing, in CSS pixels of the radar's box. */
export interface RadarFrame {
  width: number;
  height: number;
  /** Centre of the circles. */
  cx: number;
  cy: number;
  /** The rings drawn behind the title. */
  rings: number[];
  /** Radii the dots sit on, inner to outer: live sources on the first two, the rest on the last two. */
  radii: [number, number, number, number];
  /** No dot or label inside these (the title), with `gap` around them. */
  exclusions: Rect[];
  /** Label font size in px; a monospace character is 0.6 of it wide. */
  font: number;
  /** Minimum free space between two dots with their labels, and around an exclusion. */
  gap: number;
}

export interface RadarDot {
  source: SourceInfo;
  x: number;
  y: number;
  /** Dot radius. */
  r: number;
  tone: DotTone;
  /** The dot and its label: no two overlap. */
  box: Rect;
}

/** Up to this width only the rings show (no dots, no legend), as in home.css. */
export const RINGS_ONLY_MAX = 900;
/** Up to this width the rings move to the right edge, behind the left-aligned title. */
const PHONE_MAX = 720;
const DRAWING = 1280;
const RINGS = [130, 250, 370, 490, 610];
/** Live sources in the band 370-470, index and Scout collectors in 490-600, as the rings show. */
const RADII = [385, 455, 515, 585] as const;
const DOT = 6;
/** Candidate positions per circle: one every half degree. */
const STEPS = 720;

/**
 * Colour = last check. Every dot is filled with it: accent for answering, amber for slow or limited, red for failed,
 * grey for skipped or not checked yet.
 */
export function toneOf(s: SourceInfo): DotTone {
  if (s.lastOutcome === 'FAILED') return 'failed';
  if (s.lastOutcome === 'TIMED_OUT' || s.lastOutcome === 'RATE_LIMITED') return 'warn';
  if (s.mode === 'CURATED' || !s.lastOutcome || s.lastOutcome === 'SKIPPED') return 'idle';
  return 'answering';
}

/** Size = new this week from that source, when GET /sources says it; else every dot the same size. */
export function radiusOf(s: SourceInfo): number {
  const added = newThisWeek(s);
  return added === null ? DOT : 4 + Math.min(8, Math.sqrt(added) * 1.6);
}

/** The circles centred on the hero, the title block (`title`) kept free of dots. */
export function radarFrame(width: number, height: number, title: Rect | null): RadarFrame {
  const scale = Math.min(1, width / DRAWING);
  return {
    width,
    height,
    cx: width <= PHONE_MAX ? width - 80 : width / 2,
    cy: height / 2,
    rings: RINGS.map((r) => r * scale),
    radii: RADII.map((r) => r * scale) as RadarFrame['radii'],
    exclusions: title ? [title] : [],
    font: 11,
    gap: 8,
  };
}

function boxOf(x: number, y: number, r: number, label: string, f: RadarFrame): Rect {
  const half = Math.max(r, (label.length * f.font * 0.6) / 2 + 1);
  return { left: x - half, top: y - r, right: x + half, bottom: y + r + 3 + f.font * 1.3 };
}

function overlaps(a: Rect, b: Rect, gap: number): boolean {
  return (
    a.left < b.right + gap &&
    b.left < a.right + gap &&
    a.top < b.bottom + gap &&
    b.top < a.bottom + gap
  );
}

/** Inside the hero (the search box is below it), clear of the title. */
function fits(box: Rect, f: RadarFrame): boolean {
  if (box.left < 4 || box.right > f.width - 4 || box.top < 4 || box.bottom > f.height - 4)
    return false;
  return !f.exclusions.some((e) => overlaps(box, e, f.gap * 2));
}

interface Slot {
  x: number;
  y: number;
}

/** Points on one circle, each side from top to bottom. */
function circleSides(radius: number, f: RadarFrame): { left: Slot[]; right: Slot[] } {
  const left: Slot[] = [];
  const right: Slot[] = [];
  for (let k = 0; k < STEPS; k++) {
    const a = -Math.PI / 2 + (k / STEPS) * 2 * Math.PI;
    const p = { x: f.cx + Math.cos(a) * radius, y: f.cy + Math.sin(a) * radius };
    (p.x < f.cx ? left : right).push(p);
  }
  const topDown = (a: Slot, b: Slot) => a.y - b.y;
  return { left: left.sort(topDown), right: right.sort(topDown) };
}

/** Live sources on the two inner circles, the others (our index, Scout collectors) on the two outer ones. */
function byCircle(sources: SourceInfo[]): SourceInfo[][] {
  const live = sources.filter((s) => s.mode === 'LIVE');
  const rest = sources.filter((s) => s.mode !== 'LIVE');
  const split = (list: SourceInfo[]) => {
    const first = Math.ceil(list.length / 2);
    return [list.slice(0, first), list.slice(first)];
  };
  return [...split(live), ...split(rest)];
}

/**
 * A fixed slot for each source. On each circle the sources alternate left and right of the title, spread evenly over
 * the free part of that side; a dot that would touch an earlier one moves along its circle to the nearest free point,
 * then to the other side, then to the other circle of its pair (live or not). A source with no free point anywhere is
 * left out (`placeDots` returns fewer dots).
 */
export function placeDots(sources: SourceInfo[], f: RadarFrame): RadarDot[] {
  const placed: RadarDot[] = [];
  const circles = f.radii.map((r) => circleSides(r, f));
  byCircle(sources).forEach((list, c) => {
    const sides = circles[c]!;
    const sibling = circles[c % 2 === 0 ? c + 1 : c - 1]!;
    const startRight = c % 2 === 1;
    const onLeft = (i: number) => (i % 2 === 0) !== startRight;
    const counts = { left: 0, right: 0 };
    list.forEach((_, i) => counts[onLeft(i) ? 'left' : 'right']++);
    const seen = { left: 0, right: 0 };
    list.forEach((s, i) => {
      const side = onLeft(i) ? 'left' : 'right';
      const other = side === 'left' ? 'right' : 'left';
      const k = seen[side]++;
      const dot = place(s, f, placed, [
        { slots: sides[side], target: (k + 0.5) / counts[side] },
        { slots: sides[other], target: 0.5 },
        { slots: sibling[side], target: 0.5 },
        { slots: sibling[other], target: 0.5 },
      ]);
      if (dot) placed.push(dot);
    });
  });
  return placed;
}

function place(
  s: SourceInfo,
  f: RadarFrame,
  placed: RadarDot[],
  tries: { slots: Slot[]; target: number }[],
): RadarDot | null {
  const r = radiusOf(s);
  for (const { slots, target } of tries) {
    const free = slots.filter((p) => fits(boxOf(p.x, p.y, r, s.shortName, f), f));
    if (free.length === 0) continue;
    const start = Math.min(free.length - 1, Math.max(0, Math.round(target * free.length - 0.5)));
    // The target point first, then outwards: +1, -1, +2, -2, ...
    for (let d = 0; d < free.length * 2; d++) {
      const p = free[start + (d % 2 === 0 ? d / 2 : -(d + 1) / 2)];
      if (!p) continue;
      const box = boxOf(p.x, p.y, r, s.shortName, f);
      if (placed.every((o) => !overlaps(box, o.box, f.gap)))
        return { source: s, x: p.x, y: p.y, r, tone: toneOf(s), box };
    }
  }
  return null;
}

/** Where the beam meets a dot: its angle in degrees, clockwise from 3 o'clock, as the beam turns (radarClock.ts). */
export function angleOf(d: RadarDot, f: RadarFrame): number {
  const deg = (Math.atan2(d.y - f.cy, d.x - f.cx) * 180) / Math.PI;
  return (deg + 360) % 360;
}

/** True when two dots with their labels touch (used by the tests and nowhere else). */
export function touching(a: RadarDot, b: RadarDot, gap: number): boolean {
  return overlaps(a.box, b.box, gap);
}

/** True when a dot or its label is under the title or outside the hero. */
export function outOfBounds(d: RadarDot, f: RadarFrame): boolean {
  return !fits(d.box, f);
}
