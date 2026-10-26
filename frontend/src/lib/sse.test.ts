import { SseParser } from './sse';

describe('SseParser', () => {
  it('reads named events as Spring writes them (no space after the colon)', () => {
    const p = new SseParser();
    expect(p.push('event:planned\ndata:{"a":1}\n\nevent:answer\ndata:{"b":2}\n\n')).toEqual([
      { event: 'planned', data: '{"a":1}' },
      { event: 'answer', data: '{"b":2}' },
    ]);
  });

  it('joins events split across chunks, even inside a line or a CRLF', () => {
    const p = new SseParser();
    expect(p.push('event: chos')).toEqual([]);
    expect(p.push('en\r')).toEqual([]);
    expect(p.push('\ndata: {"proposed":3}\r\n')).toEqual([]);
    expect(p.push('\r\n')).toEqual([{ event: 'chosen', data: '{"proposed":3}' }]);
  });

  it('joins multi-line data, defaults the name, and skips comments and empty events', () => {
    const p = new SseParser();
    expect(p.push(': keep-alive\n\ndata: one\ndata: two\nid: 7\n\n')).toEqual([{ event: 'message', data: 'one\ntwo' }]);
  });
});
