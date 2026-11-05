import { makeCard } from '@/test/fixtures';
import { canGuide } from './GuideMe';

describe('"Set it up" is offered only on things a user installs or runs', () => {
  it.each(['MCP_SERVER', 'MODEL', 'LIBRARY', 'AGENT', 'SKILL', 'PRODUCT'] as const)(
    'a %s gets it',
    (type) => expect(canGuide(makeCard({ type }))).toBe(true),
  );

  it.each(['PAPER', 'DATASET', 'PROMPT', 'ARTICLE', 'COURSE', 'EVENT', 'NEWS', 'OTHER'] as const)(
    'a %s gets none',
    (type) => expect(canGuide(makeCard({ type }))).toBe(false),
  );
});
