import { describe, expect, it } from 'vitest';
import client from './client.ts?raw';
import en from '@/i18n/locales/en.json';

// The public bundle carries neither the back office's calls nor its copy (ADR 0045).
describe('the public bundle', () => {
  it('has no admin call in the shared API client', () => {
    expect(client).not.toMatch(/['`/]admin\//);
  });

  it('has no admin copy in the public translations', () => {
    expect(en).not.toHaveProperty('admin');
  });
});
