import { above } from '@/lib/breakpoints';
import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  sectionAnchor,
  TextSections,
  useTextSections,
  type TextValues,
} from '@/features/pages/TextSections';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import '@/features/pages/text.css';

const WIDE = above('split');

export interface TocEntry {
  id: string;
  title: string;
}

export function TextPage({
  i18nKey,
  head,
  after,
  afterToc = [],
  values,
  numbersUnavailable = false,
}: {
  i18nKey: string;
  head: ReactNode;
  after?: ReactNode;
  afterToc?: TocEntry[];
  values?: TextValues;
  numbersUnavailable?: boolean;
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
        {numbersUnavailable && <p>{t('textPage.numbersUnavailable')}</p>}
        {!wide && sections.length > 0 && (
          <details className="h2-toc-m">
            <summary>{t('textPage.onThisPage')}</summary>
            {links}
          </details>
        )}
        <TextSections i18nKey={i18nKey} values={values} />
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
