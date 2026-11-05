import { displayHost } from './format';
describe('displayHost', () => {
  it('drops www.', () => expect(displayHost('https://www.acme.example/x')).toBe('acme.example'));
  it('keeps text that is not a URL', () => expect(displayHost('not a url')).toBe('not a url'));
});
