import { createContext, type FormEvent, type HTMLAttributes, type Ref, useContext, useEffect, useId, useRef, useState } from 'react';
import { aiMark } from '@/lib/aiMark';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { SetupClient, SetupGuide as Guide, SetupOption, ToolGuide } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { Icon } from '@/components/Icon';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';

const CLIENT_KEY = 'h2.setup.client';

function rememberedClient(): string | null {
  try {
    return window.localStorage.getItem(CLIENT_KEY);
  } catch {
    return null;
  }
}

function remember(id: string) {
  try {
    window.localStorage.setItem(CLIENT_KEY, id);
  } catch {
    /* storage unavailable: the choice lasts for this page only */
  }
}

/** One copy button for a snippet or a command. */
function CopyButton({ text, primary = false }: { text: string; primary?: boolean }) {
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
    <button type="button" className={primary ? 'btn btn-primary' : 'btn btn-ghost'} onClick={() => void copy()}>
      {copied ? t('setup.copied') : t('setup.copy')}
    </button>
  );
}

/** A heading at a level that fits where the panel sits (h2 on the detail page, h4 inside a result card). */
function H({ level, ...props }: { level: number } & HTMLAttributes<HTMLHeadingElement> & { ref?: Ref<HTMLHeadingElement> }) {
  const Tag = `h${Math.min(Math.max(level, 2), 6)}` as 'h2';
  return <Tag {...props} />;
}

/** One open guide per list: opening a card's guide closes the one that was open. */
export const OpenGuideContext = createContext<{ openId: string | null; setOpenId: (id: string | null) => void } | null>(null);

/** Open state of one tool's guide: shared through [OpenGuideContext] in a list, local elsewhere. */
export function useGuideToggle(id: string) {
  const shared = useContext(OpenGuideContext);
  const [local, setLocal] = useState(false);
  const open = shared ? shared.openId === id : local;
  const toggle = () => (shared ? shared.setOpenId(open ? null : id) : setLocal(!open));
  return { open, toggle };
}

/** The "Guide me" button; [bodyId] is the panel it opens. */
export function GuideMeButton({ title, open, onToggle, bodyId, primary = false, buttonRef }: {
  title: string; open: boolean; onToggle: () => void; bodyId: string; primary?: boolean; buttonRef?: Ref<HTMLButtonElement>;
}) {
  const { t } = useTranslation();
  const name = { title, interpolation: { escapeValue: false } };
  return (
    <button ref={buttonRef} type="button" className={`btn ${primary ? 'btn-primary' : 'btn-ghost'} guide-me`} aria-expanded={open}
      aria-controls={bodyId} aria-label={primary ? undefined : t('guideMe.aria', name)} onClick={onToggle}>
      {!primary && <Icon name="sparkle" />}{t('guideMe.label')}
    </button>
  );
}

/**
 * "Guide me" on a tool: how to install, configure, use and connect it, from its own sources only. Three parts,
 * loaded when opened: snippets per AI tool from its registry entry or README config example (GET /setup, no AI),
 * steps from its README or model card (POST /guide, AI-picked, every quote and command checked against the
 * document), and a follow-up question about this tool (same endpoint, with the question). Focus moves to its
 * heading when it opens.
 */
export function GuideBody({ id, title, level }: { id: string; title: string; level: number }) {
  const { t } = useTranslation();
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => heading.current?.focus(), []);
  return (
    <div className="panel setup-panel">
      <H level={level} ref={heading} tabIndex={-1} className="setup-heading">
        {t('setup.title', { title, interpolation: { escapeValue: false } })}
      </H>
      <ConnectPart id={id} level={level + 1} />
      <StepsPart id={id} title={title} level={level + 1} />
    </div>
  );
}

/** The detail page's Guide me: a primary button, the panel under it. */
export function SetupGuidePanel({ id, title }: { id: string; title: string }) {
  const { t } = useTranslation();
  const { open, toggle } = useGuideToggle(id);
  const bodyId = `setup-${useId()}`;
  return (
    <section className="setup" aria-label={t('setup.title', { title, interpolation: { escapeValue: false } })}>
      <GuideMeButton title={title} open={open} onToggle={toggle} bodyId={bodyId} primary />
      <div id={bodyId} hidden={!open}>{open && <GuideBody id={id} title={title} level={2} />}</div>
    </section>
  );
}

function ConnectPart({ id, level }: { id: string; level: number }) {
  const { t, i18n } = useTranslation();
  const q = useApi(`setup/${id}`, (signal) => api.getResourceSetup(id, signal));
  if (q.loading) return <p className="muted small" role="status">{t('setup.loading')}</p>;
  // Without registry or README config data there is nothing to connect; the steps below still help.
  if (q.error !== undefined || !q.data || q.data.options.length === 0) return null;
  return <SetupAnswer guide={q.data} locale={i18n.language} level={level} />;
}

function SetupAnswer({ guide, locale, level }: { guide: Guide; locale: string; level: number }) {
  const { t } = useTranslation();
  const uid = useId();
  const [optionId, setOptionId] = useState(guide.options[0]!.id);
  const [clientId, setClientId] = useState(() => {
    const saved = rememberedClient();
    return guide.clients.some((c) => c.id === saved) ? saved! : guide.clients[0]!.id;
  });
  const option = guide.options.find((o) => o.id === optionId) ?? guide.options[0]!;
  const client = guide.clients.find((c) => c.id === clientId) ?? guide.clients[0]!;
  const source = guide.sources[0];

  return (
    <section className="setup-part" aria-labelledby={`${uid}-h`}>
      <H level={level} id={`${uid}-h`} className="setup-part-h">{t('setup.connectTitle')}</H>
      {guide.options.length > 1 && (
        <fieldset className="setup-options">
          <legend className="filter-legend">{t('setup.howToRun')}</legend>
          {guide.options.map((o) => (
            <label key={o.id} className="chip">
              <input type="radio" name={`${uid}-option`} value={o.id} checked={o.id === option.id} onChange={() => setOptionId(o.id)} />
              {o.label}
            </label>
          ))}
        </fieldset>
      )}

      <OptionFacts option={option} />

      <div className="setup-client">
        <label htmlFor={`${uid}-client`} className="filter-legend">{t('setup.yourTool')}</label>
        <select id={`${uid}-client`} value={client.id} onChange={(e) => { setClientId(e.target.value); remember(e.target.value); }}>
          {guide.clients.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
      </div>

      <ClientSnippet client={client} optionId={option.id} />

      {source && (
        <p className="muted small setup-source">
          <Icon name="info" />{' '}
          {source.sourceId === 'readme'
            ? t('setup.fromReadme', { date: formatDate(source.retrievedAt, locale) })
            : t('setup.from', { version: guide.version ?? t('common.unknown'), date: formatDate(source.retrievedAt, locale) })}{' '}
          <ExternalLink url={source.url} showHost={false}>{source.sourceId === 'readme' ? t('setup.readme') : t('setup.registryEntry')}</ExternalLink>
        </p>
      )}
    </section>
  );
}

/** The step-by-step guide, then the follow-up box; a question replaces the steps with its answer. */
function StepsPart({ id, title, level }: { id: string; title: string; level: number }) {
  const { t } = useTranslation();
  const uid = useId();
  const [question, setQuestion] = useState('');
  const [asked, setAsked] = useState<string | null>(null);
  const q = useApi(`tool-guide/${id}/${asked ?? ''}`, (signal) => api.askResourceGuide(id, asked ? { question: asked } : {}, signal));
  const name = { title, interpolation: { escapeValue: false } };
  function submit(e: FormEvent) {
    e.preventDefault();
    const text = question.trim();
    if (text.length >= 3) setAsked(text);
  }
  return (
    <section className="setup-part" aria-labelledby={`${uid}-h`}>
      <H level={level} id={`${uid}-h`} className="setup-part-h">{asked ? t('setup.answerTitle') : t('setup.stepsTitle')}</H>
      {asked && <p className="small"><q>{asked}</q> · <button type="button" className="btn btn-ghost" onClick={() => { setAsked(null); setQuestion(''); }}>{t('setup.backToSteps')}</button></p>}
      <div aria-live="polite" aria-busy={q.loading}>
        {q.loading ? (
          <p className="muted" role="status">{t(asked ? 'setup.answering' : 'setup.reading')}</p>
        ) : q.error !== undefined || !q.data ? (
          <div role="alert"><p>{t('setup.stepsError')}</p><button type="button" className="btn" onClick={q.reload}>{t('common.retry')}</button></div>
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
          <H level={level} className="setup-section-h">{t(`setup.section.${s.kind}`)}</H>
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

function OptionFacts({ option }: { option: SetupOption }) {
  const { t } = useTranslation();
  return (
    <div className="setup-facts">
      {option.kind === 'REMOTE' ? (
        <p>{t('setup.remote')} <code>{option.url}</code></p>
      ) : option.kind === 'COMMAND' ? (
        <p>{t('setup.command', { cmd: option.runtime })}{option.requires && <> · {t('setup.requires', { what: option.requires })}</>}</p>
      ) : (
        <p>{t('setup.package', { type: option.registryType, id: option.identifier })}{option.requires && <> · {t('setup.requires', { what: option.requires })}</>}</p>
      )}
      {option.settings.length > 0 && (
        <>
          <p className="small"><strong>{option.kind === 'REMOTE' ? t('setup.headers') : t('setup.env')}</strong></p>
          <ul className="plain-list setup-settings">
            {option.settings.map((s) => (
              <li key={s.name}>
                <code>{s.name}</code>{' '}
                <span className="muted small">
                  {s.required ? t('setup.required') : t('setup.optional')}
                  {s.secret && <> · {t('setup.secret')}</>}
                  {s.value && !s.secret && <> · {t('setup.default', { value: s.value })}</>}
                </span>
                {s.description && <div className="small">{s.description}</div>}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function ClientSnippet({ client, optionId }: { client: SetupClient; optionId: string }) {
  const { t } = useTranslation();
  const s = client.snippets.find((x) => x.optionId === optionId);
  if (!s) return null;
  return (
    <div className="setup-snippet">
      {s.supported && s.text ? (
        <>
          <div className="setup-snippet-head">
            <span className="small">{s.where && <>{t('setup.where')} <strong>{s.where}</strong></>}</span>
            <CopyButton text={s.text} primary />
          </div>
          <pre className="setup-code" data-language={s.language ?? undefined}><code>{s.text}</code></pre>
          {s.note && <p className="small muted">{s.note}</p>}
        </>
      ) : (
        <p className="notice notice-warn"><Icon name="info" /> {t('setup.unsupported', { client: client.name })} {s.note}</p>
      )}
      <p className="small muted">
        {t('setup.placeholders')} <ExternalLink url={client.docsUrl}>{t('setup.docs', { client: client.name })}</ExternalLink>
      </p>
    </div>
  );
}
