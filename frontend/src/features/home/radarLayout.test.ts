import type { SourceInfo } from '@/api/types';
import { radarSources } from '@/lib/sourceCounts';
import { STAGING_SOURCES } from '@/test/fixtures';
import {
  angleOf,
  outOfBounds,
  placeDot,
  placeDots,
  radarFrame,
  radarGroup,
  radiusOf,
  toneOf,
  touching,
  type RadarDot,
} from './radarLayout';

const external = radarSources(STAGING_SOURCES);
const live = (over: Partial<SourceInfo> = {}): SourceInfo => ({
  ...STAGING_SOURCES[0]!,
  ...over,
});

describe('the radar (ADR 0040): which sources and how they look', () => {
  it('shows only the enabled external sources: 13 of the 16 on staging', () => {
    expect(external).toHaveLength(13);
    expect(external.map((s) => s.id)).not.toContain('helper2-index');
    expect(external.map((s) => s.id)).not.toContain('helper2-semantic');
    expect(external.map((s) => s.id)).not.toContain('curated');
    expect(radarSources([live({ enabled: false })])).toEqual([]);
  });

  it('colour = last check, every dot filled: answering, amber slow or limited, red failed', () => {
    expect(toneOf(live())).toBe('answering');
    expect(toneOf(live({ mode: 'INDEXED' }))).toBe('answering');
    expect(toneOf(live({ lastOutcome: 'FAILED' }))).toBe('failed');
    expect(toneOf(live({ lastOutcome: 'TIMED_OUT' }))).toBe('warn');
    expect(toneOf(live({ lastOutcome: 'RATE_LIMITED' }))).toBe('warn');
    expect(toneOf(live({ lastOutcome: 'SKIPPED' }))).toBe('idle');
  });

  it('size = new this week, the default size when the API sends none', () => {
    expect(radiusOf(live({ newThisWeek: null }))).toBe(6);
    expect(radiusOf(live({ newThisWeek: 25 }))).toBeGreaterThan(6);
  });
});

const CATEGORIES: [string, string, NonNullable<SourceInfo['family']>][] = [
  ['prompt-libraries', 'Prompts', 'prompts-and-skills'],
  ['skills-and-gpts', 'Skills', 'prompts-and-skills'],
  ['ai-assistants', 'Assistants', 'everyday-apps'],
  ['writing', 'Writing', 'everyday-apps'],
  ['translation', 'Translation', 'everyday-apps'],
  ['meetings-and-transcription', 'Meetings', 'everyday-apps'],
  ['documents-and-pdf', 'Documents', 'everyday-apps'],
  ['research-and-papers', 'Research', 'everyday-apps'],
  ['ai-search', 'AI search', 'everyday-apps'],
  ['browser-extensions', 'Extensions', 'everyday-apps'],
  ['education-and-tutors', 'Education', 'everyday-apps'],
  ['health', 'Health', 'everyday-apps'],
  ['image-generation', 'Images', 'creative-and-media'],
  ['video-generation', 'Video', 'creative-and-media'],
  ['voice-and-audio', 'Voice, audio', 'creative-and-media'],
  ['design-and-presentations', 'Design', 'creative-and-media'],
  ['customer-support', 'Support', 'business'],
  ['voice-agents', 'Voice agents', 'business'],
  ['data-and-analytics', 'Data', 'business'],
  ['no-code', 'No-code', 'business'],
  ['marketing-and-seo', 'Marketing', 'business'],
  ['legal', 'Legal', 'business'],
  ['coding', 'Coding', 'developers'],
  ['agent-platforms', 'Agents', 'developers'],
  ['llm-frameworks', 'Frameworks', 'developers'],
  ['rag-and-vector-databases', 'RAG', 'developers'],
  ['llmops-and-evaluation', 'LLMOps', 'developers'],
  ['ai-security', 'AI security', 'developers'],
  ['fine-tuning', 'Fine-tuning', 'developers'],
  ['model-hosting', 'Hosting', 'hosting-and-infra'],
  ['ai-cloud-and-paas', 'AI cloud', 'hosting-and-infra'],
  ['ai-gateways', 'Gateways', 'hosting-and-infra'],
  ['self-hosted', 'Self-hosted', 'run-it-yourself'],
  ['local-ai', 'Local AI', 'run-it-yourself'],
];
const discovery: SourceInfo[] = CATEGORIES.map(([id, shortName, family]) => ({
  ...STAGING_SOURCES.find((s) => s.id === 'live-web-scout')!,
  id: `discovery-${id}`,
  name: `Discovery: ${shortName}`,
  shortName,
  family,
  newThisWeek: 30,
}));
const all = [...external, ...discovery];

const hero = (width: number) =>
  radarFrame(width, 340, {
    left: (width - 588) / 2,
    top: 47,
    right: (width + 588) / 2,
    bottom: 281,
  });

function seeded(seed = 7): () => number {
  let x = seed;
  return () => {
    x = (x * 1103515245 + 12345) % 2147483648;
    return x / 2147483648;
  };
}

describe.each([901, 1024, 1280, 1920])(
  'placing the %s px radar: each source when it comes on',
  (width) => {
    const frame = hero(width);

    it(`every one of the ${all.length} sources gets a spot of its own: none is ever left out`, () => {
      for (const s of all) {
        const dot = placeDot(s, frame, []);
        expect(outOfBounds(dot, frame), s.id).toBe(false);
      }
    });

    it('every source still gets a spot with 4 others on the radar, touching none of them', () => {
      const random = seeded();
      for (let round = 0; round < 60; round++) {
        const placed: RadarDot[] = [];
        for (let i = 0; i < 5; i++) {
          const s = all[Math.floor(random() * all.length)]!;
          if (placed.some((d) => d.source.id === s.id)) continue;
          const dot = placeDot(s, frame, placed);
          expect(outOfBounds(dot, frame), s.id).toBe(false);
          for (const other of placed) expect(touching(dot, other, frame.gap - 1)).toBe(false);
          placed.push(dot);
        }
      }
    });

    it.skipIf(width < 1024)('a live source sits inside the others when it has room', () => {
      const radius = (d: RadarDot) => Math.hypot(d.x - frame.cx, d.y - frame.cy);
      const inner = external.filter((s) => s.mode === 'LIVE').map((s) => placeDot(s, frame, []));
      const outer = all.filter((s) => s.mode !== 'LIVE').map((s) => placeDot(s, frame, []));
      expect(Math.max(...inner.map(radius))).toBeLessThan(Math.min(...outer.map(radius)));
    });

    it('a source comes back to the same spot while that spot is free', () => {
      for (const s of all) expect(placeDot(s, frame, [])).toEqual(placeDot(s, frame, []));
    });
  },
);

it('the radar groups: live sources, collectors and discovery categories', () => {
  expect(radarGroup(live())).toBe('live');
  expect(radarGroup(external.find((s) => s.id === 'live-web-scout')!)).toBe('index');
  expect(radarGroup(discovery[0]!)).toBe('discovery');
});

it('above 720 px the radar is a half dome that rises from the bottom middle of the hero', () => {
  for (const [width, height] of [
    [901, 340],
    [1920, 340],
  ] as const) {
    const f = radarFrame(width, height, null);
    expect(f.cx).toBe(width / 2);
    expect(f.cy).toBe(height);
    expect(f.arc).toEqual([0, 180]);
    expect(f.names).toBe(true);
    expect(f.rings.at(-1)).toBeLessThanOrEqual(width / 2);
  }
});

it('up to 720 px the radar is a quarter dome in the top right corner, its dots without names', () => {
  const f = radarFrame(375, 230, null);
  expect([f.cx, f.cy]).toEqual([375, 0]);
  expect(f.arc).toEqual([180, 270]);
  expect(f.names).toBe(false);
  expect(f.rings.at(-1)).toBeLessThanOrEqual(375 * 0.6);
});

const phoneLines = [
  { left: 16, top: 24, right: 190, bottom: 56 },
  { left: 16, top: 58, right: 355, bottom: 90 },
  { left: 16, top: 92, right: 330, bottom: 124 },
  { left: 16, top: 136, right: 350, bottom: 160 },
  { left: 16, top: 162, right: 345, bottom: 186 },
  { left: 16, top: 188, right: 250, bottom: 212 },
];

it('the dots spread over the dome: on both sides of the title, never bunched together', () => {
  const f = hero(1280);
  const dots = placeDots(all.slice(0, 10), f);
  for (const d of dots) expect(outOfBounds(d, f)).toBe(false);
  expect(dots.some((d) => d.x < f.cx)).toBe(true);
  expect(dots.some((d) => d.x > f.cx)).toBe(true);
  const gaps = dots.flatMap((a, i) =>
    dots.slice(i + 1).map((b) => Math.hypot(a.x - b.x, a.y - b.y)),
  );
  expect(Math.min(...gaps)).toBeGreaterThan(f.width / 20);
});

it('on a phone the 3 dots sit in the corner beside the lines of text, apart from each other', () => {
  const f = radarFrame(375, 230, phoneLines);
  const dots = placeDots(all.slice(0, 3), f);
  for (const d of dots) {
    expect(outOfBounds(d, f)).toBe(false);
    expect(d.x).toBeGreaterThan(375 / 2);
  }
  const gaps = dots.flatMap((a, i) =>
    dots.slice(i + 1).map((b) => Math.hypot(a.x - b.x, a.y - b.y)),
  );
  expect(Math.min(...gaps)).toBeGreaterThan(20);
});

it("the angle of a dot is clockwise from 3 o'clock, as the beam turns", () => {
  const wide = hero(1280);
  const at = (x: number, y: number) => ({ ...placeDots(external, wide)[0]!, x, y });
  expect(angleOf(at(wide.cx + 100, wide.cy), wide)).toBe(0);
  expect(angleOf(at(wide.cx, wide.cy + 100), wide)).toBe(90);
  expect(angleOf(at(wide.cx - 100, wide.cy), wide)).toBe(180);
  expect(angleOf(at(wide.cx, wide.cy - 100), wide)).toBe(270);
});

it('dots shrink with the dome, down to 60 % on a phone', () => {
  const phone = radarFrame(375, 230, phoneLines);
  expect(hero(1280).scale).toBe(1);
  expect(phone.scale).toBeGreaterThanOrEqual(0.6);
  expect(phone.scale).toBeLessThan(1);
});
