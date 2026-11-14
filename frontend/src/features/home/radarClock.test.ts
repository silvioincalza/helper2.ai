import { beamAngle, contactAt, LEAVE_MS, lastPass, REST, TURN_MS } from './radarClock';

describe('the radar clock (ADR 0040): a contact is painted when the beam passes it', () => {
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
    expect(contactAt(90, 3000, 2000, null).opacity).toBe(0);
    const painted = contactAt(90, 6250, 2000, null);
    expect(painted).toEqual({ opacity: 1, pop: 1.35, glow: 1, ping: 0 });
  });

  it('then the pop ends in 0.25 s, the glow in 0.6 s, and an afterglow fades it near the rest level by the next pass', () => {
    expect(contactAt(90, 1250 + 250, 0, null).pop).toBe(1);
    expect(contactAt(90, 1250 + 600, 0, null).glow).toBe(0);
    const late = contactAt(90, 1250 + 4900, 0, null).opacity;
    expect(late).toBeGreaterThan(REST);
    expect(late).toBeLessThan(REST + 0.1);
  });

  it('a leaving source is not painted again and is gone within 3 s', () => {
    const left = 3000;
    expect(contactAt(90, 6250, 0, left).glow).toBe(0);
    expect(contactAt(90, left + LEAVE_MS / 2, 0, left).opacity).toBeGreaterThan(0);
    expect(contactAt(90, left + LEAVE_MS, 0, left).opacity).toBe(0);
  });
});

describe('the sonar ping', () => {
  it('a ring leaves the dot as the sweep paints it and is gone 1.4 s later', () => {
    expect(contactAt(90, 1250, 0, null).ping).toBe(0);
    expect(contactAt(90, 1250 + 700, 0, null).ping).toBeCloseTo(0.5);
    expect(contactAt(90, 1250 + 1400, 0, null).ping).toBe(1);
  });

  it('a source that is leaving sends no ping', () => {
    expect(contactAt(90, 6300, 0, 6000).ping).toBe(1);
  });
});
