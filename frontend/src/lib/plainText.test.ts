import { locateQuote } from './locateQuote';
import { plainText } from './plainText';

const FENCED = '``` ollama run tinyllama:1.1b --verbose ``` From the benchmark you can see it.';

describe('plainText', () => {
  it('drops Markdown code fences and keeps the words between them', () => {
    expect(plainText(FENCED)).toBe(
      'ollama run tinyllama:1.1b --verbose From the benchmark you can see it.',
    );
  });

  it('drops the backticks of inline code', () => {
    expect(plainText('Run `ollama pull` first, then ``ollama run``.')).toBe(
      'Run ollama pull first, then ollama run.',
    );
  });

  it('leaves no gap or line break where a fence stood on its own line', () => {
    expect(plainText('Install:\n```\nnpm i acme\n```\nDone.')).toBe('Install: npm i acme Done.');
  });

  it('returns text with no backtick as it is', () => {
    expect(plainText('Translates documents between languages.')).toBe(
      'Translates documents between languages.',
    );
    expect(plainText('')).toBe('');
  });

  it.each([
    ['inside the code', 'ollama run tinyllama:1.1b'],
    ['with the fence in it', '``` ollama run tinyllama:1.1b --verbose ```'],
    ['across the closing fence', '--verbose ``` From the benchmark'],
    ['starting with a fence', '``` From the benchmark you can see it.'],
    ['after the code', 'From the benchmark'],
  ])(
    'a quote found word for word in the source is still found in the plain text: %s',
    (_, quote) => {
      expect(locateQuote(FENCED, quote)).not.toBeNull();
      const at = locateQuote(plainText(FENCED), plainText(quote));
      expect(at).not.toBeNull();
      expect(at?.match).not.toContain('`');
      expect(`${at?.before}${at?.match}${at?.after}`).toBe(plainText(FENCED));
    },
  );

  it('a quote that is only a fence is not found, so the quote is shown alone', () => {
    expect(locateQuote(plainText(FENCED), plainText('```'))).toBeNull();
  });
});
