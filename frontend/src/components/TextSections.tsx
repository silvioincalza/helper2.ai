import { useId } from 'react';
import { useTranslation } from 'react-i18next';

/** One block of a text page, as stored in the locale file. */
export interface TextSection {
  title: string;
  paragraphs?: string[];
  items?: string[];
}

/**
 * Renders the `<key>.sections` array of a locale file as headed sections. Text pages keep their
 * copy in the locale file, so translating them needs no code change.
 */
export function TextSections({ i18nKey }: { i18nKey: string }) {
  const { t } = useTranslation();
  const idPrefix = useId();
  const sections = t(i18nKey, { returnObjects: true }) as unknown;
  if (!Array.isArray(sections)) return null;
  return (
    <>
      {(sections as TextSection[]).map((s, i) => {
        const headingId = `${idPrefix}-${i}`;
        return (
          <section key={s.title} aria-labelledby={headingId} className="text-section">
            <h2 id={headingId} className="h-mid">{s.title}</h2>
            {s.paragraphs?.map((p) => <p key={p}>{p}</p>)}
            {s.items && <ul>{s.items.map((item) => <li key={item}>{item}</li>)}</ul>}
          </section>
        );
      })}
    </>
  );
}
