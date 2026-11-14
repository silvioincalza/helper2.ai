import { useTranslation } from 'react-i18next';

interface NumberedText {
  text: string;
  withoutNumbers: string;
}

export type TextEntry = string | NumberedText;

export interface TextSection {
  title: string;
  paragraphs?: TextEntry[];
  items?: TextEntry[];
}

export type TextValues = Readonly<Record<string, string>> | undefined;

export function textOf(entry: TextEntry, values: TextValues): string {
  if (typeof entry === 'string') return entry;
  return values ? entry.text : entry.withoutNumbers;
}

export function sectionAnchor(title: string): string {
  return title
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

export function useTextSections(i18nKey: string, values?: TextValues): TextSection[] {
  const { t } = useTranslation();
  const sections = t(i18nKey, { returnObjects: true, ...values }) as unknown;
  return Array.isArray(sections) ? (sections as TextSection[]) : [];
}

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
