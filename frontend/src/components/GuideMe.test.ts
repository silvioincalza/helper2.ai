import { makeCard } from '@/test/fixtures';
import { canGuide } from './GuideMe';

describe('"Set it up" is offered only on things a user installs or runs', () => {
  it.each(['MCP_SERVER', 'MODEL', 'LIBRARY', 'AGENT', 'SKILL'] as const)('a %s gets it', (type) =>
    expect(canGuide(makeCard({ type }))).toBe(true),
  );

  it.each(['PAPER', 'DATASET', 'PROMPT', 'ARTICLE', 'COURSE', 'EVENT', 'NEWS', 'OTHER'] as const)(
    'a %s gets none',
    (type) => expect(canGuide(makeCard({ type }))).toBe(false),
  );

  it('a product gets it only when it is self-hosted, a desktop app or a mobile app', () => {
    expect(canGuide(makeCard({ type: 'PRODUCT', platforms: ['web'], pricing: 'FREEMIUM' }))).toBe(
      false,
    );
    expect(canGuide(makeCard({ type: 'PRODUCT', platforms: [], pricing: null }))).toBe(false);
    expect(canGuide(makeCard({ type: 'PRODUCT', pricing: 'OPEN_SOURCE_SELF_HOSTED' }))).toBe(true);
    for (const platform of ['local', 'desktop', 'ios', 'android']) {
      expect(canGuide(makeCard({ type: 'PRODUCT', platforms: ['web', platform] }))).toBe(true);
    }
  });
});
