import { useEffect, useState, type CSSProperties } from 'react';

/** Green blips at a time, and how often they move: once per sweep (see `.radar-sweep` in app.css). */
const BLIPS = 3;
const SWEEP_MS = 7000;

type Blip = { left: number; top: number; delay: number };

/** A random spot between the inner and the outer ring, kept in the band the hero shows (in % of the rings box). */
function randomBlip(): Blip {
  const angle = Math.random() * 2 * Math.PI;
  const r = 150 + Math.random() * 330;
  const x = 550 + r * Math.cos(angle);
  const y = Math.min(820, Math.max(280, 550 + r * Math.sin(angle)));
  return { left: (x / 1100) * 100, top: (y / 1100) * 100, delay: Math.random() * 2.6 };
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Decoration behind the home hero: radar rings, a slow sweep and blips (the brand: "AI on your radar"). The green
 * blips land somewhere new on every sweep; under prefers-reduced-motion they stay put and the sweep stops (app.css).
 * Hidden from assistive tech.
 */
export function RadarRings() {
  const [blips, setBlips] = useState<Blip[]>(() => Array.from({ length: BLIPS }, randomBlip));

  useEffect(() => {
    if (prefersReducedMotion()) return;
    const id = window.setInterval(() => setBlips(Array.from({ length: BLIPS }, randomBlip)), SWEEP_MS);
    return () => window.clearInterval(id);
  }, []);

  return (
    <div className="radar-rings" aria-hidden="true">
      <svg viewBox="0 0 1100 1100" focusable="false">
        <circle cx="550" cy="550" r="180" />
        <circle cx="550" cy="550" r="300" />
        <circle cx="550" cy="550" r="420" />
        <circle cx="550" cy="550" r="540" />
        <line x1="10" y1="550" x2="1090" y2="550" />
        <line x1="550" y1="10" x2="550" y2="1090" />
      </svg>
      <svg className="radar-sweep" viewBox="0 0 1100 1100" focusable="false">
        <path d="M550 550 L550 10 A540 540 0 0 1 931.8 168.2 Z" />
        <line x1="550" y1="550" x2="931.8" y2="168.2" />
      </svg>
      <span className="radar-blip blip-a" />
      {blips.map((b, i) => (
        <span key={`${i}-${b.left}`} className="radar-blip blip-b"
          style={{ left: `${b.left}%`, top: `${b.top}%`, '--blip-delay': `${b.delay}s` } as CSSProperties} />
      ))}
    </div>
  );
}
