import type { SourceInfo } from '@/api/types';
import { newThisWeek } from '@/lib/v4Fields';

/**
 * Where the Home radar's dots go (ADR 0028). Full circles centred on the hero, the title in the middle. The radar shows a
 * few external sources at a time; each is placed when it comes on (`placeDot`), clear of the ones already shown,
 * starting from its own point, so it tends to come back to the same spot. Pure functions: the component measures, this
 * module places.
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

/** The group a source belongs to on the radar: live sources, the Scout's collectors, the discovery categories. */
export type RadarGroup = 'live' | 'index' | 'discovery';

export function radarGroup(s: SourceInfo): RadarGroup {
  if (s.mode === 'LIVE') return 'live';
  return s.family ? 'discovery' : 'index';
}

/** A number in [0, 1) from a source id: the same on every visit, spread over the circle. */
function homeOf(id: string): number {
  let h = 2166136261;
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

/** One candidate point every half degree on a circle, nearest to [start] (a turn fraction) first. */
function* aroundCircle(radius: number, start: number, f: RadarFrame): Generator<Slot> {
  const first = Math.round(start * STEPS);
  for (let d = 0; d < STEPS; d++) {
    const k = first + (d % 2 === 0 ? d / 2 : -(d + 1) / 2);
    const a = (k / STEPS) * 2 * Math.PI;
    yield { x: f.cx + Math.cos(a) * radius, y: f.cy + Math.sin(a) * radius };
  }
}

/** Every point of the hero on an 8 px grid, top to bottom: the last resort when no circle has room. */
function* anywhere(f: RadarFrame): Generator<Slot> {
  for (let y = 8; y < f.height; y += 8) for (let x = 8; x < f.width; x += 8) yield { x, y };
}

/**
 * The spot of a source that comes onto the radar now (ADR 0028), clear of the dots already on it (`placed`): live
 * sources on the two inner circles, the others on the two outer ones. It starts from the source's own point on one of
 * its circles, so a source comes back to the same spot while that is free, then moves along the circle to the nearest
 * free point, then to the other circle of its pair, then to the other pair, then anywhere in the hero. Only a few dots
 * are on the radar at a time, so every source finds a spot: none is ever left out. With no free point at all (a
 * hero too small for any label) it takes its own point anyway.
 */
export function placeDot(s: SourceInfo, f: RadarFrame, placed: RadarDot[]): RadarDot {
  const r = radiusOf(s);
  const home = homeOf(s.id);
  const pair = s.mode === 'LIVE' ? [0, 1] : [2, 3];
  const other = s.mode === 'LIVE' ? [2, 3] : [0, 1];
  // Half of the sources start on each circle of their pair.
  const order = home < 0.5 ? [...pair, ...other] : [pair[1]!, pair[0]!, other[1]!, other[0]!];
  const fitsHere = (p: Slot): RadarDot | null => {
    const box = boxOf(p.x, p.y, r, s.shortName, f);
    if (!fits(box, f) || placed.some((o) => overlaps(box, o.box, f.gap))) return null;
    return { source: s, x: p.x, y: p.y, r, tone: toneOf(s), box };
  };
  for (const c of order)
    for (const p of aroundCircle(f.radii[c as 0 | 1 | 2 | 3], (home * 2) % 1, f)) {
      const dot = fitsHere(p);
      if (dot) return dot;
    }
  for (const p of anywhere(f)) {
    const dot = fitsHere(p);
    if (dot) return dot;
  }
  const own = aroundCircle(f.radii[order[0] as 0 | 1 | 2 | 3], (home * 2) % 1, f).next()
    .value as Slot;
  return {
    source: s,
    x: own.x,
    y: own.y,
    r,
    tone: toneOf(s),
    box: boxOf(own.x, own.y, r, s.shortName, f),
  };
}

/** The sources placed one after another, each clear of the ones before (the fixed set when the radar stands still). */
export function placeDots(sources: SourceInfo[], f: RadarFrame): RadarDot[] {
  const placed: RadarDot[] = [];
  for (const s of sources) placed.push(placeDot(s, f, placed));
  return placed;
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
