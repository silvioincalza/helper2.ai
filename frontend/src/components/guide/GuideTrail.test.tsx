import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { GuideResponse } from '@/api/types';
import { GuideAnswer } from './GuideAnswer';

const base: GuideResponse = {
  status: 'PARTIAL',
  searchQuery: 'speech recognition',
  recommendations: [
    {
      resourceId: '22222222-2222-5222-8222-222222222222',
      title: 'whisper-small',
      url: 'https://huggingface.co/acme/whisper-small',
      type: 'MODEL',
      reason: 'Self-hostable.',
      quote: 'Speech recognition model you can self-host',
      sourceIds: ['hf'],
      aiReasoned: true,
      reasonCheck: 'SUPPORTED',
    },
  ],
  model: {
    provider: 'nebius-token-factory',
    fastModel: 'nvidia/nemotron-nano',
    reasoningModel: 'nvidia/nemotron-3-super',
  },
  candidatesConsidered: 2,
  rejected: 1,
  sources: [],
  notices: [],
  generatedAt: '2026-11-04T10:00:00Z',
  trail: {
    queries: ['speech recognition', 'best self-hosted speech to text'],
    removed: [{ title: 'promo', check: 'QUOTE_NOT_FOUND' }],
  },
};

const view = (answer: GuideResponse) =>
  render(
    <MemoryRouter>
      <GuideAnswer answer={answer} />
    </MemoryRouter>,
  );

describe('research trail (ADR 0018)', () => {
  it('lists each query the guide wrote, marked and labelled AI-written', () => {
    view(base);
    const queries = within(
      screen.getByRole('list', { name: 'Search queries the guide wrote' }),
    ).getAllByRole('listitem');
    expect(queries.map((q) => q.textContent)).toEqual([
      'speech recognition',
      'best self-hosted speech to text',
    ]);
    for (const q of queries) {
      expect(q.firstElementChild).toHaveAttribute('data-ai-generated', 'true');
      expect(q.firstElementChild).toHaveAttribute('data-ai-model', 'nvidia/nemotron-nano');
    }
    expect(screen.getByText('AI-written')).toBeInTheDocument();
  });

  it('lists each removed suggestion by its title and the check it failed, in our words, with no AI mark', () => {
    view(base);
    const removed = within(
      screen.getByRole('list', { name: 'Suggestions the checks removed' }),
    ).getAllByRole('listitem');
    expect(removed.map((r) => r.textContent)).toEqual(['promo: quote not found in the source']);
    expect(removed[0]!.closest('[data-ai-generated]')).toBeNull();
  });

  it('says none were removed when nothing was', () => {
    view({
      ...base,
      status: 'OK',
      rejected: 0,
      trail: { queries: ['speech recognition'], removed: [] },
    });
    expect(screen.getByText('None removed')).toBeInTheDocument();
  });

  it("a reused answer has no queries, and shows neither them nor the answer's own search query", () => {
    view({ ...base, trail: { queries: [], removed: [] } });
    expect(screen.getByText('No AI-written search terms to show')).toBeInTheDocument();
    expect(screen.queryByText(/speech recognition/)).not.toBeInTheDocument();
    expect(
      screen.queryByRole('list', { name: 'Search queries the guide wrote' }),
    ).not.toBeInTheDocument();
  });

  it('renders a query and a removed title holding HTML as plain text', () => {
    const attack = '<img src=x onerror="alert(1)">';
    const { container } = view({
      ...base,
      trail: { queries: [attack], removed: [{ title: attack, check: 'CONSTRAINT_NOT_MET' }] },
    });
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText(attack)).toBeInTheDocument();
    expect(
      screen.getByText(`${attack}: does not fit the kind or period asked for`),
    ).toBeInTheDocument();
  });
});
