import { type HTMLAttributes, type Ref, useEffect, useId, useRef, useState } from 'react';
import { aiMark } from '@/lib/aiMark';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { SetupClient, SetupGuide as Guide, SetupOption, ToolGuide } from '@/api/types';
import { ExternalLink } from '@/ui/ExternalLink';
import { Icon } from '@/ui/Icon';
import { CopyButton } from '@/ui/CopyButton';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { useMe } from '@/context/MeContext';
import { backAgain, localDay, type VisitorClock } from '@/lib/format';
import { SetupGuidesLeft, useSetupGuidesLeft } from './SetupGuidesLeft';

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
  } catch {}
}

function H({
  level,
  ...props
}: { level: number } & HTMLAttributes<HTMLHeadingElement> & { ref?: Ref<HTMLHeadingElement> }) {
  const Tag = `h${Math.min(Math.max(level, 2), 6)}` as 'h2';
  return <Tag {...props} />;
}

function GuideMeButton({
  open,
  onToggle,
  bodyId,
  leftId,
}: {
  open: boolean;
  onToggle: () => void;
  bodyId: string;
  leftId: string | undefined;
}) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      className="btn guide-me"
      aria-expanded={open}
      aria-controls={bodyId}
      aria-describedby={leftId}
      onClick={onToggle}
    >
      <Icon name="sparkle" />
      {t('guideMe.label')}
    </button>
  );
}

function GuideBody({
  id,
  title,
  level,
  open,
}: {
  id: string;
  title: string;
  level: number;
  open: boolean;
}) {
  const { t } = useTranslation();
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (open) heading.current?.focus();
  }, [open]);
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

export function SetupGuidePanel({ id, title }: { id: string; title: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [opened, setOpened] = useState(false);
  const toggle = () => {
    setOpen((o) => !o);
    setOpened(true);
  };
  const uid = useId();
  const bodyId = `setup-${uid}`;
  const leftId = `setup-left-${uid}`;
  const left = useSetupGuidesLeft();
  return (
    <section
      className="setup"
      aria-label={t('setup.title', { title, interpolation: { escapeValue: false } })}
    >
      <GuideMeButton
        open={open}
        onToggle={toggle}
        bodyId={bodyId}
        leftId={left ? leftId : undefined}
      />
      {left && <SetupGuidesLeft id={leftId} value={left} />}
      <div id={bodyId} hidden={!open}>
        {opened && <GuideBody id={id} title={title} level={2} open={open} />}
      </div>
    </section>
  );
}

function ConnectPart({ id, level }: { id: string; level: number }) {
  const { t } = useTranslation();
  const q = useApi(`setup/${id}`, (signal) => api.getResourceSetup(id, signal));
  if (q.loading)
    return (
      <p className="muted small" role="status">
        {t('setup.loading')}
      </p>
    );
  if (q.error !== undefined || !q.data || q.data.options.length === 0) return null;
  return <SetupAnswer guide={q.data} level={level} />;
}

function SetupAnswer({ guide, level }: { guide: Guide; level: number }) {
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
      <H level={level} id={`${uid}-h`} className="setup-part-h">
        {t('setup.connectTitle')}
      </H>
      {guide.options.length > 1 && (
        <fieldset className="setup-options">
          <legend className="filter-legend">{t('setup.howToRun')}</legend>
          {guide.options.map((o) => (
            <label key={o.id} className="chip">
              <input
                type="radio"
                name={`${uid}-option`}
                value={o.id}
                checked={o.id === option.id}
                onChange={() => setOptionId(o.id)}
              />
              {o.label}
            </label>
          ))}
        </fieldset>
      )}

      <OptionFacts option={option} />

      <div className="setup-client">
        <label htmlFor={`${uid}-client`} className="filter-legend">
          {t('setup.yourTool')}
        </label>
        <select
          id={`${uid}-client`}
          value={client.id}
          onChange={(e) => {
            setClientId(e.target.value);
            remember(e.target.value);
          }}
        >
          {guide.clients.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </div>

      <ClientSnippet client={client} optionId={option.id} />

      {source && (
        <p className="muted small setup-source">
          <Icon name="info" />{' '}
          {source.sourceId === 'readme'
            ? t('setup.fromReadme', { date: localDay(source.retrievedAt) })
            : t('setup.from', {
                version: guide.version ?? t('common.unknown'),
                date: localDay(source.retrievedAt),
              })}{' '}
          <ExternalLink url={source.url} showHost={false}>
            {source.sourceId === 'readme' ? t('setup.readme') : t('setup.registryEntry')}
          </ExternalLink>
        </p>
      )}
    </section>
  );
}

function StepsPart({ id, title, level }: { id: string; title: string; level: number }) {
  const { t } = useTranslation();
  const uid = useId();
  const { refresh: refreshLimits } = useMe();
  const q = useApi(`tool-guide/${id}`, (signal) =>
    api.askResourceGuide(id, signal).finally(() => {
      if (!signal.aborted) refreshLimits();
    }),
  );
  const { retry, focusRef } = useRetry(q.reload, q.loading);
  return (
    <section className="setup-part" aria-labelledby={`${uid}-h`}>
      <H level={level} id={`${uid}-h`} ref={focusRef} tabIndex={-1} className="setup-part-h">
        {t('setup.stepsTitle')}
      </H>
      <div aria-live="polite" aria-busy={q.loading}>
        {q.loading ? (
          <p className="muted" role="status">
            {t('setup.reading')}
          </p>
        ) : q.error !== undefined || !q.data ? (
          <StatePanel
            tone="error"
            level={level + 1}
            title={t('setup.stepsError')}
            next={<RetryButton onClick={retry} />}
          />
        ) : (
          <ToolSteps guide={q.data} title={title} level={level + 1} />
        )}
      </div>
    </section>
  );
}

const UNAVAILABLE: Partial<Record<string, string>> = {
  budget: 'setup.status.BUDGET',
  quota: 'setup.status.QUOTA',
  busy: 'setup.status.BUSY',
};

const BACK_AT: Partial<Record<string, string>> = {
  budget: 'setup.status.budget',
  quota: 'setup.status.quota',
};

export function toolGuideNotice(
  guide: ToolGuide,
  clock: VisitorClock = { now: Date.now() },
): { key: string; time?: string } {
  if (guide.status !== 'LLM_UNAVAILABLE' || !guide.unavailable)
    return { key: `setup.status.${guide.status}` };
  const prefix = BACK_AT[guide.unavailable];
  const back = prefix ? backAgain(prefix, guide.retryAt, clock) : undefined;
  return back ?? { key: UNAVAILABLE[guide.unavailable] ?? 'setup.status.LLM_UNAVAILABLE' };
}

function ToolSteps({ guide, title, level }: { guide: ToolGuide; title: string; level: number }) {
  const { t } = useTranslation();
  const name = { title, interpolation: { escapeValue: false } };
  if (guide.status !== 'OK') {
    const { key, time } = toolGuideNotice(guide);
    return (
      <p className="notice">
        <Icon name="info" /> {t(key, { ...name, time })}
      </p>
    );
  }
  const docs = new Map(guide.documents.map((d) => [d.key, d]));
  const anyAiText = guide.sections.some((s) => s.steps.some((st) => st.text));
  return (
    <>
      <p className="small muted">
        <Icon name="info" /> {t(anyAiText ? 'setup.stepsNote' : 'setup.stepsNoteSourceOnly')}
      </p>
      {guide.sections.map((s) => (
        <div key={s.kind} className="setup-section">
          <H level={level} className="setup-section-h">
            {t(`setup.section.${s.kind}`)}
          </H>
          <ol className="setup-steps">
            {s.steps.map((st, i) => {
              const doc = docs.get(st.source);
              const docName = doc ? t(`setup.doc.${doc.kind}`) : st.source;
              return (
                <li key={i}>
                  {st.text ? (
                    <p className="setup-step-text" {...aiMark(guide.model)}>
                      {st.text}
                    </p>
                  ) : (
                    <div className="setup-step-source">
                      <p className="small muted">
                        <strong>{t('setup.fromSource')}</strong> ·{' '}
                        {t('setup.fromSourceNote', { doc: docName })}
                      </p>
                      <blockquote>{st.quote}</blockquote>
                    </div>
                  )}
                  {st.code && (
                    <div className="setup-step-code">
                      <pre className="setup-code" data-language={st.code.language ?? undefined}>
                        <code>{st.code.text}</code>
                      </pre>
                      <CopyButton text={st.code.text} />
                    </div>
                  )}
                  {st.caution && (
                    <p className="notice notice-warn small">
                      <Icon name="warn" /> {t('setup.caution')}
                    </p>
                  )}
                  {st.text && (
                    <details className="setup-quote">
                      <summary>{t('setup.quoteLabel', { doc: docName })}</summary>
                      <blockquote>{st.quote}</blockquote>
                    </details>
                  )}
                </li>
              );
            })}
          </ol>
        </div>
      ))}
      <p className="muted small setup-source">
        {guide.documents.map((d, i) => (
          <span key={d.key}>
            {i > 0 && ' · '}
            <ExternalLink url={d.url} showHost={false}>
              {t(`setup.doc.${d.kind}`)}
            </ExternalLink>{' '}
            {t('setup.readOn', { date: localDay(d.retrievedAt) })}
          </span>
        ))}
        {guide.model && (
          <> · {t(anyAiText ? 'setup.writtenBy' : 'setup.pickedBy', { model: guide.model })}</>
        )}
      </p>
    </>
  );
}

function OptionFacts({ option }: { option: SetupOption }) {
  const { t } = useTranslation();
  return (
    <div className="setup-facts">
      {option.kind === 'REMOTE' ? (
        <p>
          {t('setup.remote')} <code>{option.url}</code>
        </p>
      ) : option.kind === 'COMMAND' ? (
        <p>
          {t('setup.command', { cmd: option.runtime })}
          {option.requires && <> · {t('setup.requires', { what: option.requires })}</>}
        </p>
      ) : (
        <p>
          {t('setup.package', { type: option.registryType, id: option.identifier })}
          {option.requires && <> · {t('setup.requires', { what: option.requires })}</>}
        </p>
      )}
      {option.settings.length > 0 && (
        <>
          <p className="small">
            <strong>{option.kind === 'REMOTE' ? t('setup.headers') : t('setup.env')}</strong>
          </p>
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
            <span className="small">
              {s.where && (
                <>
                  {t('setup.where')} <strong>{s.where}</strong>
                </>
              )}
            </span>
            <CopyButton text={s.text} primary />
          </div>
          <pre className="setup-code" data-language={s.language ?? undefined}>
            <code>{s.text}</code>
          </pre>
          {s.note && <p className="small muted">{s.note}</p>}
        </>
      ) : (
        <p className="notice notice-warn">
          <Icon name="info" /> {t('setup.unsupported', { client: client.name })} {s.note}
        </p>
      )}
      <p className="small muted">
        {t('setup.placeholders')}{' '}
        <ExternalLink url={client.docsUrl}>{t('setup.docs', { client: client.name })}</ExternalLink>
      </p>
    </div>
  );
}
