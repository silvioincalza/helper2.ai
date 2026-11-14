export const TURN_MS = 5000;
export const REST = 0.4;
const FADE_MS = 2400;
const POP_MS = 250;
const POP = 0.35;
const GLOW_MS = 600;
const PING_MS = 1400;
export const LEAVE_MS = 3000;

export function beamAngle(t: number): number {
  return ((t / TURN_MS) * 360) % 360;
}

export function lastPass(angle: number, t: number): number {
  const phase = (angle / 360) * TURN_MS;
  if (t < phase) return -Infinity;
  return phase + Math.floor((t - phase) / TURN_MS) * TURN_MS;
}

export interface Contact {
  opacity: number;
  pop: number;
  glow: number;
  ping: number;
}

const DARK: Contact = { opacity: 0, pop: 1, glow: 0, ping: 1 };

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
    ping: leftAt === null ? Math.min(1, since / PING_MS) : 1,
  };
}
