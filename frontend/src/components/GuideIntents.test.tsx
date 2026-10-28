import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import type { GuideResponse } from '@/api/types';
import { GuideAnswer } from './GuideAnswer';

const base: GuideResponse = {
  status: 'OK',
  searchQuery: 'AI tools',
  recommendations: [],
  candidatesConsidered: 0,
  sources: [],
  notices: [],
  generatedAt: '2026-10-28T18:00:00Z',
  constraints: [],
};

function view(answer: GuideResponse, onRefine = vi.fn()) {
  render(<MemoryRouter><GuideAnswer answer={answer} onRefine={onRefine} /></MemoryRouter>);
  return onRefine;
}

describe('GuideAnswer intents (ADR 0032)', () => {
  it('asks the question back with options, free text and search anyway', async () => {
    const onRefine = view({
      ...base,
      status: 'NEEDS_CLARIFICATION',
      clarification: { question: 'New tools for what?', options: ['coding', 'agents'] },
    });
    expect(screen.getByRole('heading', { name: 'New tools for what?' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'agents' }));
    expect(onRefine).toHaveBeenLastCalledWith({ clarification: 'agents' });
    await userEvent.type(screen.getByLabelText('Your answer'), 'speech to text');
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }));
    expect(onRefine).toHaveBeenLastCalledWith({ clarification: 'speech to text' });
    await userEvent.click(screen.getByRole('button', { name: 'Search anyway' }));
    expect(onRefine).toHaveBeenLastCalledWith({ clarification: '' });
  });

  it('shows how the question was read and removes a part as a structured drop', async () => {
    const onRefine = view({
      ...base,
      intent: 'NEWEST',
      window: { from: '2026-09-28', to: '2026-10-28' },
      constraints: ['free'],
    });
    expect(screen.getByText('New resources')).toBeInTheDocument();
    // With a period the server reads "newest" again, so only the period can be removed.
    expect(screen.queryByRole('button', { name: 'Remove “New resources” and ask again' })).not.toBeInTheDocument();
    expect(screen.getByText('Created 2026-09-28 – 2026-10-28')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Remove “Created 2026-09-28 – 2026-10-28” and ask again' }));
    expect(onRefine).toHaveBeenLastCalledWith({ drop: ['window'] });
    await userEvent.click(screen.getByRole('button', { name: 'Remove “free” and ask again' }));
    expect(onRefine).toHaveBeenLastCalledWith({ drop: ['constraint:free'] });
  });

  it('removes the intent when no period holds it in place', async () => {
    const onRefine = view({ ...base, intent: 'NEWEST' });
    await userEvent.click(screen.getByRole('button', { name: 'Remove “New resources” and ask again' }));
    expect(onRefine).toHaveBeenLastCalledWith({ drop: ['intent'] });
  });

  it('compares side by side and says when a source does not state something', () => {
    view({
      ...base,
      intent: 'COMPARE',
      subjects: ['Ollama', 'LM Studio'],
      comparison: {
        subjects: [
          { key: 's1', name: 'Ollama', resourceId: '11111111-1111-5111-8111-111111111111', title: 'ollama', url: 'https://github.com/ollama/ollama' },
          { key: 's2', name: 'LM Studio', resourceId: null, title: null, url: null },
        ],
        rows: [{ aspect: 'Licence', cells: [{ subjectKey: 's1', text: 'MIT', quote: 'MIT License', reasonCheck: 'SUPPORTED' }, { subjectKey: 's2', text: null, quote: null, reasonCheck: 'NOT_CHECKED' }] }],
      },
    });
    expect(screen.getByRole('columnheader', { name: 'ollama' })).toBeInTheDocument();
    expect(screen.getByText(/Not found in our sources/)).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /MIT/ })).toHaveTextContent('MIT License');
    expect(screen.getByText('Not stated by the source')).toBeInTheDocument();
  });

  it('shows each explanation sentence with the resource and the quote it rests on', () => {
    view({
      ...base,
      intent: 'WHAT_IS',
      explanation: {
        sentences: [{
          text: 'Ollama runs language models locally.', quote: 'Get up and running with large language models locally.',
          resourceId: '11111111-1111-5111-8111-111111111111', title: 'ollama', url: 'https://github.com/ollama/ollama', reasonCheck: 'SUPPORTED',
        }],
      },
    });
    expect(screen.getByRole('heading', { name: /What it is/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'ollama' })).toHaveAttribute('href', '/resource/11111111-1111-5111-8111-111111111111');
    expect(screen.getByText('Get up and running with large language models locally.')).toBeInTheDocument();
  });
});
