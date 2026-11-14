import { describe, expect, it } from 'vitest';
import client from './client.ts?raw';
import en from '@/i18n/locales/en.json';
import enLegal from '@/i18n/locales/en.legal.json';

describe('the public bundle', () => {
  it('has no admin call in the shared API client', () => {
    expect(client).not.toMatch(/['`/]admin\//);
  });

  it('has no admin copy in the public translations', () => {
    expect(en).not.toHaveProperty('admin');
    expect(enLegal).not.toHaveProperty('admin');
  });

  it('keeps the Privacy and Terms copy out of the first download: their pages load it', () => {
    expect(en).not.toHaveProperty('privacy');
    expect(en).not.toHaveProperty('terms');
  });
});

describe('the public routes', () => {
  it('have no back office route', async () => {
    const { routes } = await import('@/routes');
    const paths = JSON.stringify(routes, (key, value) => (key === 'element' ? undefined : value));
    expect(paths).not.toMatch(/"path":"admin/);
  });
});
