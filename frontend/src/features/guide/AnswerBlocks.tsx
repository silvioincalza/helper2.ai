import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { GuideResponse } from '@/api/types';
import { aiMark } from '@/lib/aiMark';
import { resourcePath } from '@/lib/searchState';
import { AiLabel } from '@/ui/AiLabel';
import { ComparisonTable } from './ComparisonTable';

export function AnswerBlocks({ answer }: { answer: GuideResponse }) {
  const explanation = answer.explanation?.sentences.length ? answer.explanation : null;
  const comparison = answer.comparison?.rows.length ? answer.comparison : null;
  if (!explanation && !comparison) return null;
  return (
    <div
      data-ai-provider={answer.aiContent?.provider}
      data-ai-models={answer.aiContent?.models.join(' ')}
    >
      {explanation && <ExplanationBlock explanation={explanation} />}
      {comparison && <ComparisonTable comparison={comparison} />}
    </div>
  );
}

function ExplanationBlock({
  explanation,
}: {
  explanation: NonNullable<GuideResponse['explanation']>;
}) {
  const { t } = useTranslation();
  return (
    <section className="guide-explain" aria-labelledby="guide-explain-h">
      <h3 id="guide-explain-h">
        {t('guide.explanation.title')} <AiLabel>{t('guide.promptAi')}</AiLabel>
      </h3>
      <ol className="plain-list">
        {explanation.sentences.map((x, i) => (
          <li key={i}>
            <span {...aiMark()}>{x.text}</span>{' '}
            <Link to={resourcePath(x.resourceId, x.title)} className="small">
              {x.title}
            </Link>
            <q>{x.quote}</q>
          </li>
        ))}
      </ol>
      <p className="muted small">{t('guide.explanation.aiNote')}</p>
    </section>
  );
}
