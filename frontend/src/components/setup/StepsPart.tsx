import { type FormEvent, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { ToolGuide } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { Icon } from '@/components/Icon';
import { Heading } from '@/components/ui/Heading';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { aiMark } from '@/lib/aiMark';
import { formatDate } from '@/lib/format';
import { CopyButton } from './CopyButton';

/**
 * Steps from the tool's README or model card (POST /guide): the model writes each instruction (labelled and marked,
 * ADR 0038), every quote and command is checked against the document. Then the follow-up box; a question replaces
 * the steps with its answer. Try again keeps focus on the part's heading.
 */
export function StepsPart({ id, title, level }: { id: string; title: string; level: number }) {
  const { t } = useTranslation();
  const uid = useId();
  const [question, setQuestion] = useState('');
  const [asked, setAsked] = useState<string | null>(null);
  const q = useApi(`tool-guide/${id}/${asked ?? ''}`, (signal) => api.askResourceGuide(id, asked ? { question: asked } : {}, signal));
  const { retry, focusRef } = useRetry(q.reload, q.loading);
  const name = { title, interpolation: { escapeValue: false } };
  function submit(e: FormEvent) {
    e.preventDefault();
    const text = question.trim();
    if (text.length >= 3) setAsked(text);
  }
  return (
    <section className="setup-part" aria-labelledby={`${uid}-h`}>
      <Heading level={level} id={`${uid}-h`} ref={focusRef} tabIndex={-1} className="setup-part-h">{asked ? t('setup.answerTitle') : t('setup.stepsTitle')}</Heading>
      {asked && <p className="small"><q>{asked}</q> · <button type="button" className="btn btn-ghost" onClick={() => { setAsked(null); setQuestion(''); }}>{t('setup.backToSteps')}</button></p>}
      <div aria-live="polite" aria-busy={q.loading}>
        {q.loading ? (
          <p className="muted" role="status">{t(asked ? 'setup.answering' : 'setup.reading')}</p>
        ) : q.error !== undefined || !q.data ? (
          <StatePanel tone="error" level={level + 1} title={t('setup.stepsError')} next={<RetryButton onClick={retry} />} />
        ) : (
          <ToolSteps guide={q.data} title={title} level={level + 1} />
        )}
      </div>
      <form className="setup-ask" onSubmit={submit}>
        <label htmlFor={`${uid}-q`} className="filter-legend">{t('setup.askLabel', name)}</label>
        <div className="setup-ask-row">
          <input id={`${uid}-q`} type="text" enterKeyHint="send" maxLength={500} value={question}
            placeholder={t('setup.askPlaceholder')} onChange={(e) => setQuestion(e.target.value)} />
          <button type="submit" className="btn btn-primary" disabled={question.trim().length < 3 || q.loading}>{t('setup.ask')}</button>
        </div>
      </form>
    </section>
  );
}

function ToolSteps({ guide, title, level }: { guide: ToolGuide; title: string; level: number }) {
  const { t, i18n } = useTranslation();
  const name = { title, interpolation: { escapeValue: false } };
  if (guide.status !== 'OK') {
    const key = guide.status === 'LLM_UNAVAILABLE' && guide.unavailable === 'budget' ? 'setup.status.BUDGET' : `setup.status.${guide.status}`;
    return <p className="notice"><Icon name="info" /> {t(key, name)}</p>;
  }
  const docs = new Map(guide.documents.map((d) => [d.key, d]));
  return (
    <>
      <p className="small muted"><Icon name="info" /> {t('setup.stepsNote')}</p>
      {guide.sections.map((s) => (
        <div key={s.kind} className="setup-section">
          <Heading level={level} className="setup-section-h">{t(`setup.section.${s.kind}`)}</Heading>
          <ol className="setup-steps">
            {s.steps.map((st, i) => {
              const doc = docs.get(st.source);
              return (
                <li key={i}>
                  <p className="setup-step-text" {...aiMark(guide.model)}>{st.text}</p>
                  {st.code && (
                    <div className="setup-step-code">
                      <pre className="setup-code" data-language={st.code.language ?? undefined}><code>{st.code.text}</code></pre>
                      <CopyButton text={st.code.text} />
                    </div>
                  )}
                  {st.caution && <p className="notice notice-warn small"><Icon name="warn" /> {t('setup.caution')}</p>}
                  <details className="setup-quote">
                    <summary>{t('setup.quoteLabel', { doc: doc ? t(`setup.doc.${doc.kind}`) : st.source })}</summary>
                    <blockquote>{st.quote}</blockquote>
                  </details>
                </li>
              );
            })}
          </ol>
        </div>
      ))}
      <p className="muted small setup-source">
        {guide.documents.map((d, i) => (
          <span key={d.key}>{i > 0 && ' · '}<ExternalLink url={d.url} showHost={false}>{t(`setup.doc.${d.kind}`)}</ExternalLink> {t('setup.readOn', { date: formatDate(d.retrievedAt, i18n.language) })}</span>
        ))}
        {guide.model && <> · {t('setup.writtenBy', { model: guide.model })}</>}
      </p>
    </>
  );
}
