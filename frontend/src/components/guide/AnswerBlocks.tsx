import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { GuideResponse } from '@/api/types';
import { aiMark } from '@/lib/aiMark';

/** One column per subject; each AI-written cell shows the quote from that subject's own text, or says it is not stated. */
export function ComparisonTable({ comparison }: { comparison: NonNullable<GuideResponse['comparison']> }) {
  const { t } = useTranslation();
  return (
    <section className="guide-compare" aria-labelledby="guide-compare-h">
      <h3 id="guide-compare-h">{t('guide.comparison.title')} <span className="badge">{t('guide.promptAi')}</span></h3>
      <table>
        <thead>
          <tr>
            <th scope="col">{t('guide.comparison.aspect')}</th>
            {comparison.subjects.map((s) => (
              <th key={s.key} scope="col">
                {s.resourceId ? <Link to={`/resource/${s.resourceId}`}>{s.title ?? s.name}</Link> : <>{s.name} <span className="muted small">({t('guide.comparison.notFound')})</span></>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {comparison.rows.map((r) => (
            <tr key={r.aspect}>
              <th scope="row"><span {...aiMark()}>{r.aspect}</span></th>
              {comparison.subjects.map((s) => {
                const cell = r.cells.find((c) => c.subjectKey === s.key);
                return (
                  <td key={s.key}>
                    {cell?.text ? <><span {...aiMark()}>{cell.text}</span>{cell.quote && <q>{cell.quote}</q>}</> : <span className="muted small">{t('guide.comparison.notStated')}</span>}
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

/** A short AI-written explanation; each sentence links the resource whose text it quotes. */
export function ExplanationBlock({ explanation }: { explanation: NonNullable<GuideResponse['explanation']> }) {
  const { t } = useTranslation();
  return (
    <section className="guide-explain" aria-labelledby="guide-explain-h">
      <h3 id="guide-explain-h">{t('guide.explanation.title')} <span className="badge">{t('guide.promptAi')}</span></h3>
      <ol className="plain-list">
        {explanation.sentences.map((x, i) => (
          <li key={i}>
            <span {...aiMark()}>{x.text}</span> <Link to={`/resource/${x.resourceId}`} className="small">{x.title}</Link>
            <q>{x.quote}</q>
          </li>
        ))}
      </ol>
      <p className="muted small">{t('guide.explanation.aiNote')}</p>
    </section>
  );
}
