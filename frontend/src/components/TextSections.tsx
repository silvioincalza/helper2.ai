import { useId } from 'react';
import { useTranslation } from 'react-i18next';

/** One block of a text page, as stored in the locale file. */
export interface TextSection {
  title: string;
  paragraphs?: string[];
  items?: string[];
}

/** A stable anchor for a section title: lower case, words joined by '-' ("Who receives data" → "who-receives-data"). */
export function sectionAnchor(title: string): string {
  return title.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

/** The `<key>.sections` array of a locale file, or an empty list when the key holds something else. */
export function useTextSections(i18nKey: string): TextSection[] {
  const { t } = useTranslation();
  const sections = t(i18nKey, { returnObjects: true }) as unknown;
  return Array.isArray(sections) ? (sections as TextSection[]) : [];
}

/**
 * Renders the `<key>.sections` array of a locale file as headed sections. Text pages keep their
 * copy in the locale file, so translating them needs no code change. [anchored]: each section gets the id of
 * [sectionAnchor], so a table of contents can link to it (ADR 0040).
 */
export function TextSections({ i18nKey, anchored = false }: { i18nKey: string; anchored?: boolean }) {
  const idPrefix = useId();
  const sections = useTextSections(i18nKey);
  if (sections.length === 0) return null;
  return (
    <>
      {sections.map((s, i) => {
        const anchor = anchored ? sectionAnchor(s.title) : undefined;
        const headingId = anchor ? `${anchor}-h` : `${idPrefix}-${i}`;
        return (
          <section key={s.title} id={anchor} aria-labelledby={headingId} className="text-section">
            <h2 id={headingId} className="h-mid">{s.title}</h2>
            {s.paragraphs?.map((p) => <p key={p}>{p}</p>)}
            {s.items && <ul>{s.items.map((item) => <li key={item}>{item}</li>)}</ul>}
          </section>
        );
      })}
    </>
  );
}
