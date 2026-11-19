import { Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { HubResponse, ResultCard } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
import { useFromHere } from '@/lib/fromResults';
import { displayHost } from '@/lib/format';
import { hubOf, type Hub } from '@/lib/hubs';
import { resourcePath } from '@/lib/searchState';
import { ListState } from '@/ui/ListState';
import { TermCommand } from '@/ui/TermCommand';
import '@/ui/term.css';
import '@/ui/listing.css';

function byline(card: ResultCard): string {
  const host = card.sources[0] ? displayHost(card.sources[0].url) : '';
  return [host, card.license ?? ''].filter(Boolean).join(' · ');
}

function HubList({ hub }: { hub: Hub }) {
  const { t } = useTranslation();
  const from = useFromHere();
  const name = t(`hubs.name.${hub.type}`);
  useTitle(name);
  const read = useApi<HubResponse>(`hub-${hub.type}`, (signal) => api.listHub(hub.type, signal));
  const items = read.data?.items ?? [];
  const feed = {
    rows: items,
    loading: read.loading,
    error: read.error !== undefined,
    reload: read.reload,
  };
  return (
    <div className="h2-app h2-term h2-listing">
      <header className="h2-term__head">
        <div className="h2-term__bar">
          <h1>{name}</h1>
          <TermCommand
            command={`helper2 browse ${hub.path}`}
            total={items.length > 0 ? items.length : null}
          />
        </div>
        <p className="h2-listing__lede">{t('hubs.lede', { name })}</p>
      </header>
      <ListState feed={feed} level={2} errorText={t('hubs.error')} emptyText={t('hubs.empty')} />
      {items.length > 0 && (
        <ul className="plain-list">
          {items.map((card) => (
            <li key={card.id} className="h2-story is-small">
              <h2 className="h2-story__h">
                <Link to={resourcePath(card.id, card.title)} state={from}>
                  {card.title}
                </Link>
              </h2>
              <p className="h2-story__by">{byline(card)}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function HubPage() {
  const hub = hubOf(useLocation().pathname);
  return hub ? <HubList key={hub.type} hub={hub} /> : null;
}
