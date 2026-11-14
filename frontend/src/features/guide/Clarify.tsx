import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { GuideResponse } from '@/api/types';
import { aiMark } from '@/lib/aiMark';
import type { Refine } from './types';
import { AiLabel } from '@/ui/AiLabel';

export function Clarify({
  clarification,
  aiContent,
  onRefine,
}: {
  clarification: NonNullable<GuideResponse['clarification']>;
  aiContent?: GuideResponse['aiContent'];
  onRefine?: Refine | undefined;
}) {
  const { t } = useTranslation();
  const [other, setOther] = useState('');
  const model = aiContent?.models[0];
  return (
    <section
      className="guide-answer clarify"
      aria-labelledby="guide-clarify-h"
      data-ai-provider={aiContent?.provider}
      data-ai-models={aiContent?.models.join(' ')}
    >
      <h3 id="guide-clarify-h">
        <span {...aiMark(model)}>{clarification.question}</span> <AiLabel />
      </h3>
      <p className="muted small">{t('guide.clarify.label')}</p>
      <div className="clarify-options">
        {clarification.options.map((o) => (
          <button
            key={o}
            type="button"
            className="btn"
            onClick={() => onRefine?.({ clarification: o })}
            {...aiMark(model)}
          >
            {o}
          </button>
        ))}
      </div>
      <form
        className="clarify-other"
        onSubmit={(e) => {
          e.preventDefault();
          if (other.trim()) onRefine?.({ clarification: other.trim() });
        }}
      >
        <label className="sr-only" htmlFor="guide-clarify-other">
          {t('guide.clarify.other')}
        </label>
        <input
          id="guide-clarify-other"
          type="text"
          maxLength={300}
          placeholder={t('guide.clarify.other')}
          value={other}
          onChange={(e) => setOther(e.target.value)}
        />
        <button type="submit" className="btn btn-primary">
          {t('guide.clarify.send')}
        </button>
        <button type="button" className="btn" onClick={() => onRefine?.({ clarification: '' })}>
          {t('guide.clarify.anyway')}
        </button>
      </form>
    </section>
  );
}
