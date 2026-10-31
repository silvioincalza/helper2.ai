import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { SetupClient, SetupGuide, SetupOption } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { Icon } from '@/components/Icon';
import { Heading } from '@/components/ui/Heading';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';
import { CopyButton } from '@/components/ui/CopyButton';

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

/**
 * Snippets per AI tool from the tool's registry entry or README config example (GET /setup, no AI). Without that data
 * there is nothing to connect, so the part is left out; the steps still help.
 */
export function ConnectPart({ id, level }: { id: string; level: number }) {
  const { t, i18n } = useTranslation();
  const q = useApi(`setup/${id}`, (signal) => api.getResourceSetup(id, signal));
  if (q.loading) return <p className="muted small" role="status">{t('setup.loading')}</p>;
  if (q.error !== undefined || !q.data || q.data.options.length === 0) return null;
  return <SetupAnswer guide={q.data} locale={i18n.language} level={level} />;
}

/** How to run it (one option at a time), what it asks for, and the snippet for the chosen AI tool, remembered. */
function SetupAnswer({ guide, locale, level }: { guide: SetupGuide; locale: string; level: number }) {
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
      <Heading level={level} id={`${uid}-h`} className="setup-part-h">{t('setup.connectTitle')}</Heading>
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
