import { fromResults } from './fromResults';

describe('fromResults', () => {
  it('keeps a path on this site as the way back', () => {
    expect(fromResults({ from: '/search?q=mcp' }).from).toBe('/search?q=mcp');
  });

  it.each(['//evil.example/x', '/\\evil.example/x', 'https://evil.example', 'search'])(
    'drops %s, which could leave the site',
    (from) => {
      expect(fromResults({ from }).from).toBeUndefined();
    },
  );

  it('drops a card head with a link that is not http(s) or a type the contract does not know', () => {
    const card = { id: 'x', title: 'X', url: 'https://x.dev', type: 'MODEL' };
    expect(fromResults({ card }).card).toEqual(card);
    expect(fromResults({ card: { ...card, url: 'javascript:alert(1)' } }).card).toBeUndefined();
    expect(fromResults({ card: { ...card, type: 'ROBOT' } }).card).toBeUndefined();
  });
});
