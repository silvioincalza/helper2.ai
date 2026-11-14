import type { ToolGuide } from '@/api/types';
import { toolGuideNotice } from './SetupGuide';

const unavailable = (why: string | null, retryAt: string | null = null): ToolGuide => ({
  resourceId: '00000000-0000-0000-0000-000000000001',
  title: 'acme',
  status: 'LLM_UNAVAILABLE',
  unavailable: why,
  retryAt,
  sections: [],
  documents: [],
  rejected: 0,
  generatedAt: '2026-11-09T12:30:00Z',
});

const zurich = {
  now: Date.parse('2026-11-09T12:30:00Z'),
  timeZone: 'Europe/Zurich',
};
const newYorkEvening = {
  now: Date.parse('2026-11-09T20:00:00Z'),
  timeZone: 'America/New_York',
};

describe('toolGuideNotice: a spent budget or quota says when new guides are back', () => {
  beforeEach(() => {
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('it-CH');
  });

  it.each(['budget', 'quota'])('%s: east of UTC, the next 00:00 UTC is tomorrow', (why) => {
    expect(toolGuideNotice(unavailable(why, '2026-11-10T00:00:00Z'), zurich)).toEqual({
      key: `setup.status.${why}BackTomorrowAt`,
      time: '01:00',
    });
  });

  it.each(['budget', 'quota'])('%s: west of UTC, the next 00:00 UTC is still today', (why) => {
    expect(toolGuideNotice(unavailable(why, '2026-11-10T00:00:00Z'), newYorkEvening)).toEqual({
      key: `setup.status.${why}BackAt`,
      time: '19:00',
    });
  });

  it('without a time, the text says after 00:00 UTC', () => {
    expect(toolGuideNotice(unavailable('budget', null), zurich)).toEqual({
      key: 'setup.status.BUDGET',
    });
    expect(toolGuideNotice(unavailable('quota'), zurich)).toEqual({ key: 'setup.status.QUOTA' });
  });

  it('busy and error have no time', () => {
    expect(toolGuideNotice(unavailable('busy', '2026-11-10T00:00:00Z'), zurich)).toEqual({
      key: 'setup.status.BUSY',
    });
    expect(toolGuideNotice(unavailable('error'), zurich)).toEqual({
      key: 'setup.status.LLM_UNAVAILABLE',
    });
    expect(toolGuideNotice({ ...unavailable(null), status: 'NO_STEPS' }, zurich)).toEqual({
      key: 'setup.status.NO_STEPS',
    });
  });
});
