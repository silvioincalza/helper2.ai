import { render } from '@testing-library/react';
import { searchTerms, withSearchTerms } from './searchTerms';

describe('searchTerms', () => {
  it('keeps distinct words of three letters or more, longest first, without common words', () => {
    expect(searchTerms('Vector database for a RAG vector')).toEqual(['database', 'vector', 'rag']);
  });

  it('splits on punctuation and keeps letters of any script', () => {
    expect(searchTerms('mcp-server, données')).toEqual(['données', 'server', 'mcp']);
  });

  it('returns nothing for an empty or short query', () => {
    expect(searchTerms('')).toEqual([]);
    expect(searchTerms('a to')).toEqual([]);
  });
});

describe('withSearchTerms', () => {
  const bold = (node: ReturnType<typeof withSearchTerms>) =>
    [...render(<p>{node}</p>).container.querySelectorAll('b.h2-hit')].map((b) => b.textContent);

  it('marks every match, ignoring case, inside longer words too', () => {
    const node = withSearchTerms('A Python Vector database: rafael/vector_database', [
      'database',
      'vector',
    ]);
    expect(bold(node)).toEqual(['Vector', 'database', 'vector', 'database']);
  });

  it('keeps the text unchanged', () => {
    const { container } = render(
      <p>{withSearchTerms('Use (regex) [chars]', ['regex', 'chars'])}</p>,
    );
    expect(container.textContent).toBe('Use (regex) [chars]');
  });

  it('returns the plain text when nothing matches', () => {
    expect(withSearchTerms('Weaviate', ['milvus'])).toBe('Weaviate');
    expect(withSearchTerms('Weaviate', [])).toBe('Weaviate');
  });
});
