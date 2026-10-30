import { locateQuote } from './locateQuote';

describe('locateQuote', () => {
  it('finds the quote like the validator does: any case, any run of whitespace', () => {
    expect(locateQuote('Runs   locally.\nMIT Licensed', 'runs locally. mit licensed')).toEqual({ before: '', match: 'Runs   locally.\nMIT Licensed', after: '' });
  });

  it('treats regex characters in the quote as plain text', () => {
    expect(locateQuote('Supports C++ (and C#) bindings', 'C++ (and C#)')?.match).toBe('C++ (and C#)');
  });

  it('returns null when the quote is not in the text, so the quote is shown alone', () => {
    expect(locateQuote('Some description', 'a title-only quote')).toBeNull();
    expect(locateQuote('Some description', '   ')).toBeNull();
  });
});
