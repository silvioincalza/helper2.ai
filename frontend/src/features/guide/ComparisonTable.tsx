/* eslint-disable jsx-a11y/no-redundant-roles, jsx-a11y/no-interactive-element-to-noninteractive-role */
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { GuideResponse } from '@/api/types';
import { aiMark } from '@/lib/aiMark';
import { resourcePath } from '@/lib/searchState';
import { AiLabel } from '@/ui/AiLabel';

export function ComparisonTable({
  comparison,
}: {
  comparison: NonNullable<GuideResponse['comparison']>;
}) {
  const { t } = useTranslation();
  return (
    <section className="guide-compare" aria-labelledby="guide-compare-h">
      <h3 id="guide-compare-h">
        {t('guide.comparison.title')} <AiLabel>{t('guide.promptAi')}</AiLabel>
      </h3>
      <table role="table">
        <thead role="rowgroup">
          <tr role="row">
            <th role="columnheader" scope="col" className="guide-compare__aspect">
              {t('guide.comparison.aspect')}
            </th>
            {comparison.subjects.map((s) => (
              <th key={s.key} role="columnheader" scope="col">
                {s.resourceId ? (
                  <Link to={resourcePath(s.resourceId, s.title ?? s.name)}>
                    {s.title ?? s.name}
                  </Link>
                ) : (
                  <>
                    {s.name} <span className="muted small">({t('guide.comparison.notFound')})</span>
                  </>
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody role="rowgroup">
          {comparison.rows.map((r) => (
            <tr key={r.aspect} role="row">
              <th role="rowheader" scope="row">
                <span {...aiMark()}>{r.aspect}</span>
              </th>
              {comparison.subjects.map((s) => {
                const cell = r.cells.find((c) => c.subjectKey === s.key);
                return (
                  <td key={s.key} role="cell">
                    <span className="guide-compare__tool" aria-hidden="true">
                      {s.title ?? s.name}
                    </span>
                    {cell?.text ? (
                      <>
                        <span {...aiMark()}>{cell.text}</span>
                        {cell.quote && <q>{cell.quote}</q>}
                      </>
                    ) : (
                      <span className="muted small">{t('guide.comparison.notStated')}</span>
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">{t('guide.comparison.aiNote')}</p>
    </section>
  );
}
