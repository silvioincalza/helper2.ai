import { screen, within } from '@testing-library/react';
import en from '@/i18n/locales/en.json';
import type { TextSection } from '@/components/TextSections';
import { sectionAnchor } from '@/components/TextSections';
import { axeViolations } from '@/test/a11y';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const PAGES: Array<{
  path: string;
  title: string;
  sections: TextSection[];
  /** Sections after the locale ones that "On this page" links to as well (Mission: how the guide answers). */
  more?: Array<{ id: string; title: string }>;
}> = [
  {
    path: '/mission',
    title: en.mission.title,
    sections: en.mission.sections as TextSection[],
    more: [
      { id: 'how-the-guide-answers', title: en.how.title },
      { id: 'never-guessed', title: en.how.rulesTitle },
    ],
  },
  { path: '/privacy', title: en.privacy.title, sections: en.privacy.sections as TextSection[] },
  { path: '/terms', title: en.terms.title, sections: en.terms.sections as TextSection[] },
];

function narrowScreen(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

describe('Feature: text pages', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sectionAnchor turns a title into a stable id', () => {
    expect(sectionAnchor('Who receives data')).toBe('who-receives-data');
    expect(sectionAnchor('Cookies and browser storage')).toBe('cookies-and-browser-storage');
  });

  it.each(PAGES)(
    'Scenario: on a wide screen, "On this page" for $path links to every section',
    async ({ path, sections, more = [] }) => {
      narrowScreen(true);
      mockApi();
      renderApp(path);
      const toc = await screen.findByRole('navigation', { name: 'On this page' });
      const links = within(toc).getAllByRole('link');
      const all = [
        ...sections.map((s) => ({ id: sectionAnchor(s.title), title: s.title })),
        ...more,
      ];
      expect(links.map((l) => l.textContent)).toEqual(all.map((s) => s.title));
      for (const { id, title } of all) {
        expect(within(toc).getByRole('link', { name: title })).toHaveAttribute('href', `#${id}`);
        expect(document.getElementById(id)).not.toBeNull();
      }
    },
  );

  it.each(PAGES)(
    'Scenario: the text of $path is exactly the text in en.json',
    async ({ path, title, sections }) => {
      narrowScreen(true);
      mockApi();
      renderApp(path);
      await settle();
      expect(screen.getByRole('heading', { level: 1, name: title })).toBeInTheDocument();
      for (const s of sections) {
        const region = screen.getByRole('region', { name: s.title });
        for (const line of [...(s.paragraphs ?? []), ...(s.items ?? [])]) {
          expect(within(region).getByText(line, { exact: true })).toBeInTheDocument();
        }
      }
    },
  );

  it('Scenario: the Terms say an answer with no pick, or a question that fails, does not count against the daily guide questions', async () => {
    narrowScreen(true);
    mockApi();
    renderApp('/terms');
    await settle();
    expect(
      screen.getByText(
        /an answer with no pick, or a question that fails, does not count either \(up to 30 a day\)/,
      ),
    ).toBeInTheDocument();
  });

  it('Scenario: on a phone, "On this page" is a collapsible list under the head', async () => {
    narrowScreen(false);
    mockApi();
    renderApp('/privacy');
    await settle();
    expect(screen.queryByRole('navigation', { name: 'On this page' })).not.toBeInTheDocument();
    const details = screen.getByText('On this page', { selector: 'summary' }).closest('details');
    expect(details).not.toBeNull();
    expect(details).not.toHaveAttribute('open');
    expect(within(details as HTMLElement).getAllByRole('link', { hidden: true })).toHaveLength(
      en.privacy.sections.length,
    );
  });

  it.each(['/mission', '/privacy', '/terms'])(
    'Scenario: %s has no accessibility violations',
    async (path) => {
      narrowScreen(true);
      mockApi();
      renderApp(path);
      await settle();
      expect(await axeViolations(document.body)).toEqual([]);
    },
  );
});
