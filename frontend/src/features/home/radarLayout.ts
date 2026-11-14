import type { SourceInfo } from '@/api/types';
import { newThisWeek } from '@/lib/sourceCounts';

export type DotTone = 'answering' | 'warn' | 'failed' | 'idle';

export interface Rect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface RadarFrame {
  width: number;
  height: number;
  cx: number;
  cy: number;
  rings: number[];
  radii: [number, number, number, number];
  exclusions: Rect[];
  font: number;
  gap: number;
  scale: number;
  arc: [number, number];
  names: boolean;
}

export interface RadarDot {
  source: SourceInfo;
  x: number;
  y: number;
  r: number;
  tone: DotTone;
  box: Rect;
}

const RINGS = [0.2, 0.4, 0.6, 0.8, 1];
const RADII = [0.72, 0.8, 0.88, 0.96] as const;
const CORNER_RADII = [0.45, 0.6, 0.8, 0.95] as const;
const DOME_HEIGHT = 1.5;
const CORNER = 0.6;
export const PHONE_MAX = 720;
const EDGE = 8;
const FULL_SIZE = 480;
const SMALLEST = 0.6;
const DOT = 6;
const STEPS = 720;

export function toneOf(s: SourceInfo): DotTone {
  if (s.lastOutcome === 'FAILED') return 'failed';
  if (s.lastOutcome === 'TIMED_OUT' || s.lastOutcome === 'RATE_LIMITED') return 'warn';
  if (s.mode === 'CURATED' || !s.lastOutcome || s.lastOutcome === 'SKIPPED') return 'idle';
  return 'answering';
}

export function radiusOf(s: SourceInfo, scale = 1): number {
  const added = newThisWeek(s);
  return (added === null ? DOT : 4 + Math.min(8, Math.sqrt(added) * 1.6)) * scale;
}

function domeRadius(width: number, height: number): number {
  return Math.max(0, Math.min(width / 2 - EDGE, height * DOME_HEIGHT));
}

export function radarFrame(width: number, height: number, title: Rect | Rect[] | null): RadarFrame {
  const phone = width <= PHONE_MAX;
  const outer = phone ? Math.min(width * CORNER, height) : domeRadius(width, height);
  return {
    width,
    height,
    cx: phone ? width : width / 2,
    cy: phone ? 0 : height,
    rings: RINGS.map((r) => r * outer),
    radii: (phone ? CORNER_RADII : RADII).map((r) => r * outer) as RadarFrame['radii'],
    exclusions: title === null ? [] : Array.isArray(title) ? title : [title],
    font: 11,
    gap: 8,
    scale: Math.min(1, Math.max(SMALLEST, outer / FULL_SIZE)),
    arc: phone ? [180, 270] : [0, 180],
    names: !phone,
  };
}

function boxOf(x: number, y: number, r: number, label: string, f: RadarFrame): Rect {
  if (!f.names) return { left: x - r, top: y - r, right: x + r, bottom: y + r };
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

function fits(box: Rect, f: RadarFrame): boolean {
  if (box.left < 4 || box.right > f.width - 4 || box.top < 4 || box.bottom > f.height - 4)
    return false;
  return !f.exclusions.some((e) => overlaps(box, e, f.gap * 2));
}

interface Slot {
  x: number;
  y: number;
}

export type RadarGroup = 'live' | 'index' | 'discovery';

export function radarGroup(s: SourceInfo): RadarGroup {
  if (s.mode === 'LIVE') return 'live';
  return s.family ? 'discovery' : 'index';
}

function homeOf(id: string): number {
  let h = 2166136261;
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

function* aroundCircle(radius: number, start: number, f: RadarFrame): Generator<Slot> {
  const first = Math.round(start * STEPS);
  for (let d = 0; d < STEPS; d++) {
    const k = first + (d % 2 === 0 ? d / 2 : -(d + 1) / 2);
    const a = (k / STEPS) * 2 * Math.PI;
    yield { x: f.cx + Math.cos(a) * radius, y: f.cy + Math.sin(a) * radius };
  }
}

function* anywhere(f: RadarFrame): Generator<Slot> {
  for (let y = 8; y < f.height; y += 8) for (let x = 8; x < f.width; x += 8) yield { x, y };
}

export function placeDot(s: SourceInfo, f: RadarFrame, placed: RadarDot[]): RadarDot {
  const r = radiusOf(s, f.scale);
  const home = homeOf(s.id);
  const pair = s.mode === 'LIVE' ? [0, 1] : [2, 3];
  const other = s.mode === 'LIVE' ? [2, 3] : [0, 1];
  const order = home < 0.5 ? [...pair, ...other] : [pair[1]!, pair[0]!, other[1]!, other[0]!];
  const fitsHere = (p: Slot): RadarDot | null => {
    const box = boxOf(p.x, p.y, r, s.shortName, f);
    if (!fits(box, f) || placed.some((o) => overlaps(box, o.box, f.gap))) return null;
    return { source: s, x: p.x, y: p.y, r, tone: toneOf(s), box };
  };
  const pick = (slots: Iterable<Slot>): RadarDot | null => {
    let best: RadarDot | null = null;
    let room = -1;
    for (const p of slots) {
      const dot = fitsHere(p);
      if (!dot) continue;
      if (placed.length === 0) return dot;
      const near = Math.min(...placed.map((o) => Math.hypot(o.x - dot.x, o.y - dot.y)));
      if (near > room) {
        best = dot;
        room = near;
      }
    }
    return best;
  };
  const rings = (cs: number[]) =>
    cs.flatMap((c) => [...aroundCircle(f.radii[c as 0 | 1 | 2 | 3], (home * 2) % 1, f)]);
  const dot = pick(rings(order.slice(0, 2))) ?? pick(rings(order.slice(2))) ?? pick(anywhere(f));
  if (dot) return dot;
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

export function placeDots(sources: SourceInfo[], f: RadarFrame): RadarDot[] {
  const placed: RadarDot[] = [];
  for (const s of sources) placed.push(placeDot(s, f, placed));
  return placed;
}

export function angleOf(d: RadarDot, f: RadarFrame): number {
  const deg = (Math.atan2(d.y - f.cy, d.x - f.cx) * 180) / Math.PI;
  return (deg + 360) % 360;
}

export function touching(a: RadarDot, b: RadarDot, gap: number): boolean {
  return overlaps(a.box, b.box, gap);
}

export function outOfBounds(d: RadarDot, f: RadarFrame): boolean {
  return !fits(d.box, f);
}
