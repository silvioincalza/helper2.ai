import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { sectionAnchor, TextSections, useTextSections } from '@/components/TextSections';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import '@/pages/text.css';

/** The table of contents sits beside the text from this width; below it, it folds into a list under the head. */
const WIDE = '(min-width: 861px)';

/** A section of [after] that "On this page" links to as well: its element id and its heading. */
export interface TocEntry {
  id: string;
  title: string;
}

/**
 * A text page (ADR 0040, in design v4 by ADR 0042): the head ([head]: h1, date, lede, contact), the `<i18nKey>`
 * sections in a readable column, then [after]. "On this page" links to every section and to [afterToc]: a list
 * beside the text on a wide screen, a collapsible list under the head on a phone. The text itself comes from the
 * locale file, unchanged.
 */
export function TextPage({
  i18nKey,
  head,
  after,
  afterToc = [],
}: {
  i18nKey: string;
  head: ReactNode;
  after?: ReactNode;
  afterToc?: TocEntry[];
}) {
  const { t } = useTranslation();
  const wide = useMediaQuery(WIDE);
  const tocId = useId();
  const sections: TocEntry[] = [
    ...useTextSections(i18nKey).map((s) => ({ id: sectionAnchor(s.title), title: s.title })),
    ...afterToc,
  ];
  const links = (
    <ol className="h2-toc__list">
      {sections.map((s) => (
        <li key={s.id}>
          <a href={`#${s.id}`}>{s.title}</a>
        </li>
      ))}
    </ol>
  );
  return (
    <div className="h2-prose h2-text-layout">
      <article className="h2-text-page">
        {head}
        {!wide && sections.length > 0 && (
          <details className="h2-toc-m">
            <summary>{t('textPage.onThisPage')}</summary>
            {links}
          </details>
        )}
        <TextSections i18nKey={i18nKey} />
        {after}
      </article>
      {wide && sections.length > 0 && (
        <nav className="h2-toc" aria-labelledby={tocId}>
          <h2 id={tocId} className="h2-toc__title">
            {t('textPage.onThisPage')}
          </h2>
          {links}
        </nav>
      )}
    </div>
  );
}
