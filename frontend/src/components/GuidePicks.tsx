import { useState } from 'react';
import { Link } from 'react-router';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import type { GuideRecommendation, GuideResponse, GuideStep } from '@/api/types';
import { Icon } from '@/components/Icon';
import { PicksBar, type PicksState } from '@/components/ui/PicksBar';
import type { GuideStream } from '@/hooks/useGuideStream';
import { aiMark } from '@/lib/aiMark';
import type { Refinement } from '@/lib/searchState';

/** One of the guide's picks: its place (#1, #2, #3), the recommendation behind it and the model that wrote its reason. */
export interface GuidePick {
  n: number;
  rec: GuideRecommendation;
  model: string | undefined;
}

/** The picks of an answer, by resource id: only recommendations the model chose and the evidence gate kept. */
export function guidePicks(answer: GuideResponse | null): Map<string, GuidePick> {
  const picks = new Map<string, GuidePick>();
  const model = answer?.model?.reasoningModel;
  for (const rec of answer?.recommendations ?? []) {
    if (rec.aiReasoned) picks.set(rec.resourceId, { n: picks.size + 1, rec, model });
  }
  return picks;
}

/** A follow-up the user asks from the answer: their reply to our question, or parts of the reading to remove. */
type Refine = (r: Partial<Refinement>) => void;

/** What went wrong, in words: the server's explanation, else why the stream failed, else a generic line. */
function guideError(err: unknown, t: TFunction): string {
  if (!(err instanceof ApiError)) return t('guide.error');
  if (err.stream) return t(`guide.streamFailure.${err.stream}`);
  return err.problem?.detail ?? t('guide.error');
}

/** The data of each step the stream has sent so far, by kind. */
type Found = { [K in GuideStep['kind']]?: Extract<GuideStep, { kind: K }>['data'] };

function found(steps: GuideStep[]): Found {
  const out: Found = {};
  for (const s of steps) (out as Record<string, unknown>)[s.kind] = s.data;
  return out;
}

/** Why there is no pick, when it is not the evidence: no model, the daily limit, or an answer of another kind. */
function noPickReason(answer: GuideResponse, t: TFunction): string | undefined {
  if (answer.notices.some((n) => n.code === 'GUIDE_DAILY_QUOTA')) return t('guide.notice.GUIDE_DAILY_QUOTA');
  if (answer.status === 'LLM_UNAVAILABLE' || answer.status === 'NO_CANDIDATES') return t(`guide.status.${answer.status}`);
  if (answer.comparison?.rows.length) return t('picks.compared');
  if (answer.explanation?.sentences.length) return t('picks.explained');
  return undefined;
}

/**
 * The guide's answer to the question in the URL, in the picks bar above the result list (ADR 0040, 6): while it works,
 * how many candidates it reads; then the picks as chips #1, #2, #3, or one line when there is none; its question back
 * when the need is vague (ADR 0032). A chip selects its row ([onPick]); a pick that is not among the results on the
 * page ([listed]) links to its resource page. How the guide read the question and the rest of the answer
 * (explanation, comparison, prompt) sit under the bar, the rest folded away so nothing moves by itself.
 */
export function GuidePicks({ guide, picks, listed, onPick, onClose }: {
  guide: GuideStream;
  picks: Map<string, GuidePick>;
  listed: Set<string>;
  onPick: (id: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { question, steps, answer, error, busy, refine, retry } = guide;
  const clarification = answer?.status === 'NEEDS_CLARIFICATION' ? answer.clarification : null;
  const step = found(steps);

  let state: PicksState;
  if (error !== null) state = { kind: 'error' };
  else if (clarification) state = { kind: 'ask' };
  else if (busy || !answer) state = { kind: 'reading', candidates: step.retrieved?.candidates };
  else if (picks.size > 0) state = { kind: 'picks' };
  else state = { kind: 'none', reason: noPickReason(answer, t) };

  const reading = answer ?? { ...step.understood, constraints: step.planned?.constraints };

  return (
    <PicksBar state={state} question={question} onClose={onClose} closeLabel={t('guide.close')} ai={answer?.aiContent}
      footer={(
        <>
          {!clarification && <Understood reading={reading} onRefine={busy ? undefined : refine} />}
          {answer?.notices.filter((n) => n.code === 'GUIDE_SUBJECT_NOT_FOUND').map((n) => (
            <p key={n.message} className="h2-picks__note"><Icon name="info" /> {n.message}</p>
          ))}
          {answer && !clarification && <AnswerDetails answer={answer} />}
        </>
      )}>
      {state.kind === 'error' && (
        <div className="h2-picks__error">
          <p role="alert"><Icon name="warn" /> {guideError(error, t)}</p>
          <button type="button" className="btn btn-primary" onClick={retry}><Icon name="refresh" /> {t('common.retry')}</button>
        </div>
      )}
      {clarification && <Clarify clarification={clarification} model={answer?.aiContent?.models[0]} onRefine={refine} />}
      {state.kind === 'picks' && (
        <ol className="plain-list h2-picks__chips" aria-label={t('picks.chips')}>
          {[...picks.values()].map(({ n, rec }) => (
            <li key={rec.resourceId}>
              {listed.has(rec.resourceId) ? (
                <button type="button" className="chip h2-pick-chip" onClick={() => onPick(rec.resourceId)}>
                  <span className="h2-pick-chip__n">#{n}</span> {rec.title}
                </button>
              ) : (
                <Link className="chip h2-pick-chip" to={`/resource/${rec.resourceId}`}>
                  <span className="h2-pick-chip__n">#{n}</span> {rec.title}
                </Link>
              )}
            </li>
          ))}
        </ol>
      )}
    </PicksBar>
  );
}

/** How the guide read the question: from the answer, or from the stream's `understood` and `planned` events. */
type Reading = { [K in 'intent' | 'subjects' | 'window' | 'constraints' | 'generatedAt']?: GuideResponse[K] | undefined };

/**
 * How the guide read the question, as chips: the kind of answer, the named subjects, the period and the
 * requirements. ✕ re-asks without that part (sent as a structured removal, never as text to the model); not while
 * the guide still works.
 */
function Understood({ reading, onRefine }: { reading: Reading; onRefine?: Refine | undefined }) {
  const { t } = useTranslation();
  const intent = reading.intent ?? 'SUGGEST';
  // `ai`: the planner wrote the label. Intent and period labels are our own text; subjects are the user's own words.
  const parts: { key: string; label: string; drop?: string | undefined; ai?: boolean }[] = [];
  // A period alone reads as "newest" on the server, so removing that intent would come straight back: the period's ✕ is the way out.
  const intentFromWindow = intent === 'NEWEST' && reading.window != null;
  if (intent !== 'SUGGEST') parts.push({ key: 'intent', label: t(`guide.intent.${intent}`), drop: intentFromWindow ? undefined : 'intent' });
  for (const s of reading.subjects ?? []) parts.push({ key: `s:${s}`, label: s });
  if (reading.window) {
    const today = (reading.generatedAt ?? new Date().toISOString()).slice(0, 10);
    const next = reading.window.from >= today;
    parts.push({
      key: 'window',
      label: t(next ? 'guide.windowNext' : 'guide.window', { from: reading.window.from, to: reading.window.to }),
      drop: 'window',
    });
  }
  for (const c of reading.constraints ?? []) parts.push({ key: `c:${c}`, label: c, drop: `constraint:${c}`, ai: true });
  if (parts.length === 0) return null;
  return (
    <p className="understood">
      <span className="muted">{t('guide.understood')}</span>
      {parts.map((p) => (
        <span key={p.key} className="understood-chip">
          {p.ai ? <span {...aiMark()}>{p.label}</span> : p.label}
          {p.drop && onRefine && (
            <button type="button" aria-label={t('guide.remove', { part: p.label })} onClick={() => onRefine({ drop: [p.drop!] })}>×</button>
          )}
        </span>
      ))}
    </p>
  );
}

/** The guide's question back when the need was too vague: one click on an option, free text, or search anyway. */
function Clarify({ clarification, model, onRefine }: {
  clarification: NonNullable<GuideResponse['clarification']>;
  model: string | undefined;
  onRefine: Refine;
}) {
  const { t } = useTranslation();
  const [other, setOther] = useState('');
  // The planner wrote the question and the options; the label, the free-text field and its buttons are ours.
  return (
    <div className="clarify">
      <h3 className="clarify-question" {...aiMark(model)}>{clarification.question}</h3>
      <p className="muted small">{t('guide.clarify.label')}</p>
      <div className="clarify-options">
        {clarification.options.map((o) => (
          <button key={o} type="button" className="btn" onClick={() => onRefine({ clarification: o })} {...aiMark(model)}>{o}</button>
        ))}
      </div>
      <form className="clarify-other" onSubmit={(e) => { e.preventDefault(); if (other.trim()) onRefine({ clarification: other.trim() }); }}>
        <label className="sr-only" htmlFor="guide-clarify-other">{t('guide.clarify.other')}</label>
        <input id="guide-clarify-other" type="text" maxLength={300} placeholder={t('guide.clarify.other')} value={other} onChange={(e) => setOther(e.target.value)} />
        <button type="submit" className="btn btn-primary">{t('guide.clarify.send')}</button>
        <button type="button" className="btn" onClick={() => onRefine({ clarification: '' })}>{t('guide.clarify.anyway')}</button>
      </form>
    </div>
  );
}

/**
 * The rest of the answer, folded under the bar: how it was made (search terms, candidates, the evidence check, the
 * models), and the explanation, comparison or prompt when the question asked for one. Open by the user only.
 */
function AnswerDetails({ answer }: { answer: GuideResponse }) {
  const { t } = useTranslation();
  const rejected = answer.rejected ?? 0;
  const parts = [
    answer.comparison?.rows.length ? t('guide.comparison.title') : null,
    answer.explanation?.sentences.length ? t('guide.explanation.title') : null,
    answer.prompt ? t('guide.promptTitle') : null,
  ].filter(Boolean);
  return (
    <details className="h2-picks__details">
      <summary>{parts.length ? t('picks.detailsWith', { parts: parts.join(', ') }) : t('picks.details')}</summary>
      <p className="muted small">
        {/* The planner may have written the query even when the answering model failed (aiContent says so). */}
        <span {...(answer.aiContent ? aiMark(answer.aiContent.models[0]) : {})}>{t('guide.searchedFor', { q: answer.searchQuery })}</span>
        {' · '}{t('guide.considered', { n: answer.candidatesConsidered })}
        {answer.status !== 'NEEDS_CLARIFICATION' && <>{' · '}{t(`guide.status.${answer.status}`)}</>}
        {rejected > 0 && <>{' · '}{t('guide.rejected', { count: rejected })}</>}
      </p>
      {answer.explanation && answer.explanation.sentences.length > 0 && <ExplanationBlock explanation={answer.explanation} />}
      {answer.comparison && answer.comparison.rows.length > 0 && <ComparisonTable comparison={answer.comparison} />}
      {answer.prompt && <PromptBox text={answer.prompt.text} basedOn={answer.prompt.basedOn} />}
      {answer.model && (
        <p className="muted small">
          {t('guide.answeredBy', { reasoning: answer.model.reasoningModel, fast: answer.model.fastModel, provider: answer.model.provider })}
        </p>
      )}
    </details>
  );
}

/** One column per subject; each AI-written cell shows the quote from that subject's own text, or says it is not stated. */
function ComparisonTable({ comparison }: { comparison: NonNullable<GuideResponse['comparison']> }) {
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
function ExplanationBlock({ explanation }: { explanation: NonNullable<GuideResponse['explanation']> }) {
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

function PromptBox({ text, basedOn }: { text: string; basedOn: { resourceId: string; title: string }[] }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard unavailable: the text is still selectable */
    }
  }
  return (
    <section className="guide-prompt" aria-labelledby="guide-prompt-h">
      <div className="guide-prompt-head">
        <h3 id="guide-prompt-h">{t('guide.promptTitle')} <span className="badge">{t('guide.promptAi')}</span></h3>
        <button type="button" className="btn btn-primary" onClick={() => void copy()}>{copied ? t('guide.copied') : t('guide.copy')}</button>
      </div>
      <pre className="guide-prompt-text" {...aiMark()}>{text}</pre>
      {basedOn.length > 0 && (
        <p className="small guide-prompt-based">
          {t('guide.basedOn')}{' '}
          {basedOn.map((b, i) => (
            <span key={b.resourceId}>{i > 0 && ', '}<Link to={`/resource/${b.resourceId}`}>{b.title}</Link></span>
          ))}
        </p>
      )}
      <p className="small guide-prompt-based">{t('guide.promptReview')}</p>
    </section>
  );
}
