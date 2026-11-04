import type { TFunction } from 'i18next';
import type { GuideResponse, Notice } from '@/api/types';
import { foldNoticeExclusions, noPickReason } from './GuideAnswer';

const t = ((key: string) => key) as unknown as TFunction;
const notice = (code: Notice['code']): Notice => ({ code, message: code });

function answer(status: GuideResponse['status'], codes: Notice['code'][] = [], model = false): GuideResponse {
  return {
    status, searchQuery: 'q', recommendations: [], prompt: null, aiContent: null, candidatesConsidered: 0, rejected: 0,
    model: model ? { provider: 'p', fastModel: 'f', reasoningModel: 'r' } : null,
    sources: [], notices: codes.map(notice), generatedAt: '2026-11-04T10:00:00Z',
  };
}

describe('noPickReason: quota > all sources failed > model unavailable > no candidates', () => {
  it('the daily quota wins over everything else', () => {
    expect(noPickReason(answer('LLM_UNAVAILABLE', ['ALL_SOURCES_FAILED', 'GUIDE_DAILY_QUOTA']), t)).toBe('guide.notice.GUIDE_DAILY_QUOTA');
  });

  it('no source answered wins over a model that is down and over an empty catalog', () => {
    expect(noPickReason(answer('LLM_UNAVAILABLE', ['ALL_SOURCES_FAILED']), t)).toBe('guide.notice.ALL_SOURCES_FAILED');
    expect(noPickReason(answer('NO_CANDIDATES', ['ALL_SOURCES_FAILED']), t)).toBe('guide.notice.ALL_SOURCES_FAILED');
  });

  it('a model that is down wins over an empty catalog; the quota needs the model to be down', () => {
    expect(noPickReason(answer('LLM_UNAVAILABLE'), t)).toBe('guide.status.LLM_UNAVAILABLE');
    expect(noPickReason(answer('NO_CANDIDATES', ['GUIDE_DAILY_QUOTA']), t)).toBe('guide.status.NO_CANDIDATES');
  });

  it('otherwise the subject not found, then "nothing fits" when a model chose, then no reason', () => {
    expect(noPickReason({ ...answer('PARTIAL', [], true), notices: [{ code: 'GUIDE_SUBJECT_NOT_FOUND', message: 'X was not found.' }] }, t))
      .toBe('X was not found.');
    expect(noPickReason(answer('PARTIAL', [], true), t)).toBe('guide.noFit');
    expect(noPickReason(answer('PARTIAL'), t)).toBeUndefined();
  });
});

describe('foldNoticeExclusions', () => {
  it('hides "no source answered" only when the bar already says it', () => {
    expect(foldNoticeExclusions(answer('NO_CANDIDATES', ['ALL_SOURCES_FAILED']), 0)).toContain('ALL_SOURCES_FAILED');
    expect(foldNoticeExclusions(answer('OK', ['ALL_SOURCES_FAILED'], true), 1)).not.toContain('ALL_SOURCES_FAILED');
    expect(foldNoticeExclusions(answer('LLM_UNAVAILABLE', ['ALL_SOURCES_FAILED', 'GUIDE_DAILY_QUOTA']), 0)).not.toContain('ALL_SOURCES_FAILED');
  });

  it('always hides the query expansion and the guide notices', () => {
    expect(foldNoticeExclusions(answer('OK'), 1)).toEqual(['QUERY_EXPANDED', 'GUIDE_DAILY_QUOTA', 'GUIDE_SUBJECT_NOT_FOUND']);
  });
});
