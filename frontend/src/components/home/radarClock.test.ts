import { beamAngle, contactAt, LEAVE_MS, lastPass, REST, TURN_MS } from './radarClock';

describe('the radar clock (ADR 0028): a contact is painted when the beam passes it', () => {
  it('the beam turns once every 5 s, clockwise from 3 o’clock', () => {
    expect(beamAngle(0)).toBe(0);
    expect(beamAngle(TURN_MS / 4)).toBe(90);
    expect(beamAngle(TURN_MS + TURN_MS / 2)).toBe(180);
  });

  it('knows when the beam last passed an angle', () => {
    expect(lastPass(90, 1000)).toBe(-Infinity);
    expect(lastPass(90, 1250)).toBe(1250);
    expect(lastPass(90, 6000)).toBe(1250);
    expect(lastPass(90, 6250)).toBe(6250);
  });

  it('a new source stays dark until the beam first passes it', () => {
    // At 90° the beam passes at 1250, 6250, ...; the source arrives at 2000.
    expect(contactAt(90, 3000, 2000, null).opacity).toBe(0);
    const painted = contactAt(90, 6250, 2000, null);
    expect(painted).toEqual({ opacity: 1, pop: 1.35, glow: 1 });
  });

  it('then the pop ends in 0.25 s, the glow in 0.6 s, and it fades to the rest level', () => {
    expect(contactAt(90, 1250 + 250, 0, null).pop).toBe(1);
    expect(contactAt(90, 1250 + 600, 0, null).glow).toBe(0);
    const late = contactAt(90, 1250 + 4900, 0, null).opacity;
    expect(late).toBeGreaterThan(REST);
    expect(late).toBeLessThan(REST + 0.05);
  });

  it('a leaving source is not painted again and is gone within 3 s', () => {
    const left = 3000;
    // Passed at 1250, left at 3000: the pass at 6250 does not paint it.
    expect(contactAt(90, 6250, 0, left).glow).toBe(0);
    expect(contactAt(90, left + LEAVE_MS / 2, 0, left).opacity).toBeGreaterThan(0);
    expect(contactAt(90, left + LEAVE_MS, 0, left).opacity).toBe(0);
  });
});
