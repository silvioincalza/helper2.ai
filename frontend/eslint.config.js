import { readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import globals from 'globals';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

const ADR = 'ADR 0050';
const BASE = ['api', 'lib', 'i18n', 'styles'];
const SHARED_MAY_IMPORT = {
  api: BASE,
  lib: BASE,
  i18n: BASE,
  styles: BASE,
  context: [...BASE, 'context'],
  hooks: [...BASE, 'context', 'hooks'],
  ui: [...BASE, 'context', 'hooks', 'ui'],
};
const SHARED = Object.keys(SHARED_MAY_IMPORT);
const COMPOSES = {
  home: ['feeds', 'search'],
  search: ['guide', 'monetisation'],
  resource: ['setup', 'monetisation'],
};

const anyOf = (names) => `(?:${names.join('|')})(?:/|$)`;
const onlyFrom = (allowed, message) => ({
  regex: `^@/(?!${anyOf([...allowed, 'test'])})`,
  message,
});

const importStyle = (folder) => [
  {
    regex: '^\\.(?!/[^/]+$)',
    message: `Write @/ for a file of another folder, ./ only for a file of the same folder (${ADR}).`,
  },
  ...(folder
    ? [
        {
          regex: `^@/${folder}/[^/]+$`,
          message: `Write ./ for a file of the same folder (${ADR}).`,
        },
      ]
    : []),
];

function boundary(folder) {
  const [top, feature] = folder.split('/');
  if (top === '')
    return [
      onlyFrom(
        [...SHARED, 'features', 'shell'],
        `The public app imports no back office file (${ADR}).`,
      ),
    ];
  if (top === 'test') return [];
  if (top === 'shell')
    return [
      onlyFrom(
        [...SHARED, 'features', 'shell'],
        `shell/ imports shared code, features and its own files (${ADR}).`,
      ),
    ];
  if (top === 'admin')
    return [
      onlyFrom(
        [...SHARED, 'admin', 'shell/RouteError'],
        `admin/ imports shared code, its own files and shell/RouteError, never a feature (${ADR}).`,
      ),
    ];
  if (top === 'features') {
    const own = [feature, ...(COMPOSES[feature] ?? [])];
    return [
      {
        regex: `^@/features/(?!${anyOf(own)})`,
        message: `A feature imports no file of another feature. Move the shared piece to ui/, lib/ or hooks/, or name the composition in COMPOSES (${ADR}).`,
      },
      onlyFrom(
        [...SHARED, 'features'],
        `A feature imports shared code and its own files, never shell/ or admin/ (${ADR}).`,
      ),
    ];
  }
  const allowed = SHARED_MAY_IMPORT[top] ?? BASE;
  return [
    onlyFrom(allowed, `${top}/ imports only ${allowed.map((a) => `${a}/`).join(', ')} (${ADR}).`),
  ];
}

const asSelector = ({ regex, message }) => ({
  selector: `:matches(ImportExpression, CallExpression[callee.object.name='vi']) > Literal[value=/${regex.replaceAll('/', '\\x2F')}/]`,
  message,
});

function importRules(folder, patterns) {
  return {
    files: [`src/${folder ? `${folder}/` : ''}*.{ts,tsx}`],
    rules: {
      'no-restricted-imports': ['error', { patterns }],
      'no-restricted-syntax': ['error', ...patterns.map(asSelector)],
    },
  };
}

const src = join(import.meta.dirname, 'src');
const folders = readdirSync(src, { recursive: true, withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => relative(src, join(entry.parentPath, entry.name)));

const imports = [
  ...['', ...folders].map((folder) =>
    importRules(folder, [...importStyle(folder), ...boundary(folder)]),
  ),
  { ...importRules('', importStyle('')), files: ['src/*.test.{ts,tsx}'] },
];

export default tseslint.config(
  {
    ignores: ['dist', 'dist-admin', 'node_modules', 'src/api/schema.ts', 'src/api/admin-schema.ts'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  jsxA11y.flatConfigs.recommended,
  reactHooks.configs.flat.recommended,
  {
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      'no-empty': ['error', { allowEmptyCatch: true }],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
  ...imports,
  prettier,
);
