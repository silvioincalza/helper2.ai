import { describe, expect, it } from 'vitest';
import client from './client.ts?raw';
import en from '@/i18n/locales/en.json';

// The public bundle carries neither the back office's calls nor its copy (ADR 0026).
describe('the public bundle', () => {
  it('has no admin call in the shared API client', () => {
    expect(client).not.toMatch(/['`/]admin\//);
  });

  it('has no admin copy in the public translations', () => {
    expect(en).not.toHaveProperty('admin');
  });
});

// The back office is its own web app (ADR 0032): the public routes do not know it.
describe('the public routes', () => {
  it('have no back office route', async () => {
    const { routes } = await import('@/routes');
    const paths = JSON.stringify(routes, (key, value) => (key === 'element' ? undefined : value));
    expect(paths).not.toMatch(/"path":"admin/);
  });
});
