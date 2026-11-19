import { trimOrNull } from './adminModel';

describe('trimOrNull: an optional text field as the API takes it', () => {
  it('trims the text', () => expect(trimOrNull('  a mistake ')).toBe('a mistake'));
  it('is null for empty or blank text', () => {
    expect(trimOrNull('')).toBeNull();
    expect(trimOrNull('   ')).toBeNull();
  });
});
