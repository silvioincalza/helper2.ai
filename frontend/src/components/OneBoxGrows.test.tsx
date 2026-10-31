import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

// jsdom has no layout: the box's content height grows one 28px line per 30 characters.
const lineOf = (el: HTMLTextAreaElement) => Math.max(1, Math.ceil(el.value.length / 30));
let restore: PropertyDescriptor | undefined;

beforeAll(() => {
  restore = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'scrollHeight');
  Object.defineProperty(HTMLTextAreaElement.prototype, 'scrollHeight', {
    configurable: true,
    get(this: HTMLTextAreaElement) { return 20 + 28 * lineOf(this); },
  });
});
afterAll(() => {
  if (restore) Object.defineProperty(HTMLTextAreaElement.prototype, 'scrollHeight', restore);
});

describe('Feature: the box grows with the text', () => {
  it('Scenario: a long question wraps onto more lines and the box grows to fit it', async () => {
    mockApi();
    renderApp('/');
    const box = await screen.findByRole('textbox', { name: 'Search AI resources' }) as HTMLTextAreaElement;
    expect(box.tagName).toBe('TEXTAREA');
    await settle();
    expect(box.style.height).toBe('48px');

    await userEvent.type(box, 'an open source MCP server so my agent can query a Postgres database');
    expect(box.style.height).toBe('104px');

    await userEvent.clear(box);
    expect(box.style.height).toBe('48px');
  });

  it('Scenario: a pasted line break becomes a space, because Enter searches or asks', async () => {
    mockApi();
    renderApp('/');
    const box = await screen.findByRole('textbox', { name: 'Search AI resources' }) as HTMLTextAreaElement;
    fireEvent.change(box, { target: { value: 'vector database\nfor RAG' } });
    expect(box.value).toBe('vector database for RAG');
  });
});
