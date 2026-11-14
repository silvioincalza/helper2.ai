import type { TFunction } from 'i18next';
import type { GuideResponse, Notice } from '@/api/types';
import { foldNoticeExclusions, noPickCode, noPickReason } from './GuideAnswer';

const t = ((key: string) => key) as unknown as TFunction;
const notice = (code: Notice['code']): Notice => ({ code, message: code });

function answer(
  status: GuideResponse['status'],
  codes: Notice['code'][] = [],
  model = false,
): GuideResponse {
  return {
    status,
    searchQuery: 'q',
    recommendations: [],
    aiContent: null,
    candidatesConsidered: 0,
    rejected: 0,
    model: model ? { provider: 'p', fastModel: 'f', reasoningModel: 'r' } : null,
    sources: [],
    notices: codes.map(notice),
    generatedAt: '2026-11-04T10:00:00Z',
  };
}

describe('noPickReason: quota > all sources failed > model unavailable > no candidates', () => {
  it('the daily quota wins over everything else', () => {
    expect(
      noPickReason(answer('LLM_UNAVAILABLE', ['ALL_SOURCES_FAILED', 'GUIDE_DAILY_QUOTA']), t),
    ).toBe('guide.notice.GUIDE_DAILY_QUOTA');
  });

  it('a full network says it is shared, not that the asker used up their own answers', () => {
    expect(
      noPickReason(answer('LLM_UNAVAILABLE', ['ALL_SOURCES_FAILED', 'GUIDE_NETWORK_QUOTA']), t),
    ).toBe('guide.notice.GUIDE_NETWORK_QUOTA');
  });

  it('no source answered wins over a model that is down and over an empty catalog', () => {
    expect(noPickReason(answer('LLM_UNAVAILABLE', ['ALL_SOURCES_FAILED']), t)).toBe(
      'guide.notice.ALL_SOURCES_FAILED',
    );
    expect(noPickReason(answer('NO_CANDIDATES', ['ALL_SOURCES_FAILED']), t)).toBe(
      'guide.notice.ALL_SOURCES_FAILED',
    );
  });

  it('a guide that ran out of time says so, after the quota and the sources, before a model that is down', () => {
    expect(noPickReason(answer('LLM_UNAVAILABLE', ['GUIDE_OUT_OF_TIME']), t)).toBe(
      'guide.notice.GUIDE_OUT_OF_TIME',
    );
    expect(
      noPickReason(answer('LLM_UNAVAILABLE', ['GUIDE_OUT_OF_TIME', 'GUIDE_DAILY_QUOTA']), t),
    ).toBe('guide.notice.GUIDE_DAILY_QUOTA');
    expect(
      noPickReason(answer('LLM_UNAVAILABLE', ['GUIDE_OUT_OF_TIME', 'ALL_SOURCES_FAILED']), t),
    ).toBe('guide.notice.ALL_SOURCES_FAILED');
  });

  it('a guide whose model budget is used up says so, after the sources, before a model that is down', () => {
    expect(noPickReason(answer('LLM_UNAVAILABLE', ['GUIDE_BUDGET_SPENT']), t)).toBe(
      'guide.notice.GUIDE_BUDGET_SPENT',
    );
    expect(
      noPickReason(answer('LLM_UNAVAILABLE', ['GUIDE_BUDGET_SPENT', 'ALL_SOURCES_FAILED']), t),
    ).toBe('guide.notice.ALL_SOURCES_FAILED');
    expect(noPickReason(answer('NO_CANDIDATES', ['GUIDE_BUDGET_SPENT']), t)).toBe(
      'guide.status.NO_CANDIDATES',
    );
  });

  describe.each([
    ['GUIDE_BUDGET_SPENT', 'budgetBackAt', 'budgetBackTomorrowAt'],
    ['GUIDE_DAILY_QUOTA', 'dailyQuotaBackAt', 'dailyQuotaBackTomorrowAt'],
    ['GUIDE_NETWORK_QUOTA', 'networkQuotaBackAt', 'networkQuotaBackTomorrowAt'],
  ] as const)(
    '%s says when AI answers are back, in the time of the visitor',
    (code, today, tomorrow) => {
      const refused = (retryAt?: string | null): GuideResponse => ({
        ...answer('LLM_UNAVAILABLE'),
        notices: [retryAt === undefined ? notice(code) : { ...notice(code), retryAt }],
      });
      const said = vi.fn((key: string) => key) as unknown as TFunction;
      const browser = (language: string) =>
        vi.spyOn(window.navigator, 'language', 'get').mockReturnValue(language);
      const zurich = { now: Date.parse('2026-11-09T12:30:00Z'), timeZone: 'Europe/Zurich' };

      beforeEach(() => browser('it-CH'));

      it('later the same local day: the hour and minute', () => {
        noPickReason(refused('2026-11-09T14:00:00Z'), said, zurich);

        expect(said).toHaveBeenLastCalledWith(`guide.notice.${today}`, { time: '15:00' });
      });

      it('the next UTC day that is still today for the visitor is not called tomorrow', () => {
        browser('en-US');

        noPickReason(refused('2026-11-10T00:00:00Z'), said, {
          ...zurich,
          timeZone: 'America/Los_Angeles',
        });

        expect(said).toHaveBeenLastCalledWith(`guide.notice.${today}`, {
          time: expect.stringMatching(/^4:00\sPM$/),
        });
      });

      it('on the next local day: tomorrow and the time', () => {
        noPickReason(refused('2026-11-10T00:00:00Z'), said, zurich);

        expect(said).toHaveBeenLastCalledWith(`guide.notice.${tomorrow}`, { time: '01:00' });
      });

      it('a time after local midnight is tomorrow, though the same UTC day', () => {
        browser('ja-JP');

        noPickReason(refused('2026-11-09T16:00:00Z'), said, { ...zurich, timeZone: 'Asia/Tokyo' });

        expect(said).toHaveBeenLastCalledWith(`guide.notice.${tomorrow}`, { time: '1:00' });
      });

      it.each([
        ['no time', undefined],
        ['a null time', null],
        ['a time that is past', '2026-11-09T12:00:00Z'],
        ['the present instant', '2026-11-09T12:30:00Z'],
        ['a time that cannot be read', 'soon'],
      ])('%s falls back to the text without a time', (_, retryAt) => {
        expect(noPickReason(refused(retryAt), t, zurich)).toBe(`guide.notice.${code}`);
      });
    },
  );

  it('a model that is down wins over an empty catalog; the quota needs the model to be down', () => {
    expect(noPickReason(answer('LLM_UNAVAILABLE'), t)).toBe('guide.status.LLM_UNAVAILABLE');
    expect(noPickReason(answer('NO_CANDIDATES', ['GUIDE_DAILY_QUOTA']), t)).toBe(
      'guide.status.NO_CANDIDATES',
    );
  });

  it('otherwise the subject not found, then "nothing fits" when a model chose, then no reason', () => {
    expect(
      noPickCode({
        ...answer('PARTIAL', [], true),
        notices: [{ code: 'GUIDE_SUBJECT_NOT_FOUND', message: 'not found', subject: 'X' }],
      }),
    ).toBe('GUIDE_SUBJECT_NOT_FOUND');
    expect(noPickReason(answer('PARTIAL', [], true), t)).toBe('guide.noFit');
    expect(noPickReason(answer('PARTIAL'), t)).toBeUndefined();
  });
});

describe('foldNoticeExclusions', () => {
  it('hides "no source answered" only when the bar already says it', () => {
    expect(foldNoticeExclusions(answer('NO_CANDIDATES', ['ALL_SOURCES_FAILED']), 0)).toContain(
      'ALL_SOURCES_FAILED',
    );
    expect(foldNoticeExclusions(answer('OK', ['ALL_SOURCES_FAILED'], true), 1)).not.toContain(
      'ALL_SOURCES_FAILED',
    );
    expect(
      foldNoticeExclusions(
        answer('LLM_UNAVAILABLE', ['ALL_SOURCES_FAILED', 'GUIDE_DAILY_QUOTA']),
        0,
      ),
    ).not.toContain('ALL_SOURCES_FAILED');
  });

  it('always hides the query expansion and the guide notices', () => {
    expect(foldNoticeExclusions(answer('OK'), 1)).toEqual([
      'QUERY_EXPANDED',
      'GUIDE_DAILY_QUOTA',
      'GUIDE_NETWORK_QUOTA',
      'GUIDE_SUBJECT_NOT_FOUND',
      'GUIDE_OUT_OF_TIME',
      'GUIDE_BUDGET_SPENT',
    ]);
  });
});
