import { useTranslation } from 'react-i18next';

/**
 * A sentence that states numbers from GET /policy (ADR 0042 decision 13): [text] with `{{name}}` variables, and
 * [withoutNumbers], the same sentence in words that need no number, shown while the numbers load or when they cannot.
 * A page never types a number the server may not apply.
 */
interface NumberedText {
  text: string;
  withoutNumbers: string;
}

/** A paragraph or list item of a text page: plain text, or text with numbers from GET /policy. */
export type TextEntry = string | NumberedText;

/** One block of a text page, as stored in the locale file. */
export interface TextSection {
  title: string;
  paragraphs?: TextEntry[];
  items?: TextEntry[];
}

/** The policy values a text page interpolates, by variable name; undefined while they load or when they cannot. */
export type TextValues = Readonly<Record<string, string>> | undefined;

/** The words to show for [entry]: with its numbers when [values] are in, otherwise its sentence without numbers. */
export function textOf(entry: TextEntry, values: TextValues): string {
  if (typeof entry === 'string') return entry;
  return values ? entry.text : entry.withoutNumbers;
}

/** A stable anchor for a section title: lower case, words joined by '-' ("Who receives data" → "who-receives-data"). */
export function sectionAnchor(title: string): string {
  return title
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/**
 * The `<key>.sections` array of a locale file, or an empty list when the key holds something else. With [values],
 * their `{{name}}` variables are filled in.
 */
export function useTextSections(i18nKey: string, values?: TextValues): TextSection[] {
  const { t } = useTranslation();
  const sections = t(i18nKey, { returnObjects: true, ...values }) as unknown;
  return Array.isArray(sections) ? (sections as TextSection[]) : [];
}

/**
 * Renders the `<key>.sections` array of a locale file as headed sections, with the policy [values] in their sentences. Text pages keep their
 * copy in the locale file, so translating them needs no code change. Each section gets the id of
 * [sectionAnchor], so the table of contents can link to it (ADR 0040).
 */
export function TextSections({ i18nKey, values }: { i18nKey: string; values?: TextValues }) {
  const sections = useTextSections(i18nKey, values);
  if (sections.length === 0) return null;
  return (
    <>
      {sections.map((s) => {
        const anchor = sectionAnchor(s.title);
        const headingId = `${anchor}-h`;
        return (
          <section key={s.title} id={anchor} aria-labelledby={headingId} className="text-section">
            <h2 id={headingId} className="h-mid">
              {s.title}
            </h2>
            {s.paragraphs?.map((p) => {
              const text = textOf(p, values);
              return <p key={text}>{text}</p>;
            })}
            {s.items && (
              <ul>
                {s.items.map((item) => {
                  const text = textOf(item, values);
                  return <li key={text}>{text}</li>;
                })}
              </ul>
            )}
          </section>
        );
      })}
    </>
  );
}
