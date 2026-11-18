import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const read = (file: string) => readFileSync(join(process.cwd(), file), 'utf8');

describe('admin layout styles', () => {
  it('Scenario: the admin page has the same page column as the public site', () => {
    expect(read('src/admin/main.tsx')).toMatch(/import '@\/styles\/shell\.css';/);
  });

  it('Scenario: on a wide screen the review detail stays in view while the list scrolls', () => {
    const css = read('src/admin/admin.css');
    const wide = /@media \(min-width: 1100px\) \{((?:[^{}]*\{[^}]*\})*)\s*\}/.exec(css)?.[1] ?? '';
    expect(wide).toMatch(/\.h2-admin__detail \{[^}]*position: sticky;[^}]*overflow-y: auto;/);
  });
});
