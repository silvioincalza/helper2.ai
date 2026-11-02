import type { TFunction } from 'i18next';
import { readingParts } from './reading';

const t = ((key: string) => key) as unknown as TFunction;

describe('readingParts', () => {
  it('a requirement can be removed when it fits in the URL', () => {
    const [part] = readingParts({ constraints: ['free'] }, '2026-11-02', t);
    expect(part).toMatchObject({ label: 'free', drop: 'constraint:free', ai: true });
  });

  it('a requirement too long for the URL shows no ✕: removing it would do nothing', () => {
    const long = 'works offline on a laptop with no GPU and supports more than forty languages';
    expect(`constraint:${long}`.length).toBeGreaterThan(80);
    const [part] = readingParts({ constraints: [long] }, '2026-11-02', t);
    expect(part).toMatchObject({ label: long, drop: undefined, ai: true });
  });
});
