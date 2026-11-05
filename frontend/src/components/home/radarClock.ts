/**
 * The radar's one clock (ADR 0042, decision 4), like a navigation radar (PPI): the beam turns once every 5 s, and a
 * contact is painted only when the beam passes its angle. Then it pops, glows in its own colour and fades to a rest
 * level until the next pass. A source that leaves is not painted again and fades out. Pure functions of the clock
 * time `t` (ms, only running while the radar moves): HomeRadar draws what they return on each animation frame.
 */

/** One turn of the beam. */
export const TURN_MS = 5000;
/** A painted contact fades to this opacity until the next pass. */
export const REST = 0.22;
/** How fast it fades to REST, the pop (scale 1.35 to 1) and the glow. */
const FADE_MS = 1200;
const POP_MS = 250;
const POP = 0.35;
const GLOW_MS = 600;
/** A source that leaves is gone after this long. */
export const LEAVE_MS = 3000;

/** The beam's leading edge, in degrees clockwise from 3 o'clock. */
export function beamAngle(t: number): number {
  return ((t / TURN_MS) * 360) % 360;
}

/** When the beam last passed `angle`, at or before `t`; -Infinity before its first pass. */
export function lastPass(angle: number, t: number): number {
  const phase = (angle / 360) * TURN_MS;
  if (t < phase) return -Infinity;
  return phase + Math.floor((t - phase) / TURN_MS) * TURN_MS;
}

export interface Contact {
  opacity: number;
  /** Scale of the dot. */
  pop: number;
  /** 1 = full glow, 0 = none. */
  glow: number;
}

const DARK: Contact = { opacity: 0, pop: 1, glow: 0 };

/**
 * How a dot looks at `t`. It came on the radar at `addedAt` and is first painted at the next pass; `leftAt` (when it
 * left) stops the painting, and it fades to 0 over LEAVE_MS.
 */
export function contactAt(
  angle: number,
  t: number,
  addedAt: number,
  leftAt: number | null,
): Contact {
  const painted = lastPass(angle, leftAt === null ? t : Math.min(t, leftAt));
  if (painted < addedAt) return DARK;
  const since = t - painted;
  const leaving = leftAt === null ? 1 : Math.max(0, 1 - (t - leftAt) / LEAVE_MS);
  return {
    opacity: (REST + (1 - REST) * Math.exp(-since / FADE_MS)) * leaving,
    pop: 1 + POP * Math.max(0, 1 - since / POP_MS),
    glow: Math.max(0, 1 - since / GLOW_MS) * leaving,
  };
}
