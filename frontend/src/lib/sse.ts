interface SseEvent {
  event: string;
  data: string;
}

export class SseParser {
  private buffer = '';
  private event = '';
  private data: string[] = [];

  push(text: string): SseEvent[] {
    this.buffer += text;
    const out: SseEvent[] = [];
    let nl: number;
    while ((nl = this.buffer.search(/\r\n|\r|\n/)) !== -1) {
      const line = this.buffer.slice(0, nl);
      const width = this.buffer.startsWith('\r\n', nl) ? 2 : 1;
      if (width === 1 && this.buffer[nl] === '\r' && nl === this.buffer.length - 1) break;
      this.buffer = this.buffer.slice(nl + width);
      this.line(line, out);
    }
    return out;
  }

  private line(line: string, out: SseEvent[]) {
    if (line === '') {
      if (this.data.length > 0)
        out.push({ event: this.event || 'message', data: this.data.join('\n') });
      this.event = '';
      this.data = [];
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
  }
}
