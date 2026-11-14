import { Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { OriginLabel } from '@/ui/Badges';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { displayHost, safeHref } from '@/lib/format';
import { locateQuote } from '@/lib/locateQuote';
import { plainText } from '@/lib/plainText';
import { rowSourceNames } from '@/lib/rowSources';
import { resourcePath } from '@/lib/searchState';
import { withSearchTerms } from '@/lib/searchTerms';
import type { GuidePick } from '@/lib/selection';
import { BookmarkButton } from '../saved/BookmarkButton';
import { toFromResults } from './fromResults';
import { ResultDate } from './ResultDate';
import { AiLabel } from '@/ui/AiLabel';

export const rowAnchor = (id: string) => `result-${id}`;

export function ResultRow({
  card,
  pick,
  stale,
  terms = [],
}: {
  card: ResultCard;
  pick: GuidePick | undefined;
  stale: ReadonlySet<string>;
  terms?: readonly string[];
}) {
  const { t } = useTranslation();
  const location = useLocation();
  const host = displayHost(card.url);
  const sources = rowSourceNames(card.sources, t('results.fromCatalog')).join(' · ');
  const staleHere = card.sources.some((s) => stale.has(s.sourceId));
  return (
    <li id={rowAnchor(card.id)} data-id={card.id} className={pick ? 'h2-row is-pick' : 'h2-row'}>
      <p className="h2-row__meta">
        <span className="h2-row__type">{t(`type.${card.type}`)}</span>
        <span aria-hidden="true"> · </span>
        <RowHost url={card.url} host={host} />
        <ResultDate card={card} separator />
        {pick && <span className="h2-row__pick">{t('results.pick', { rank: pick.rank })}</span>}
      </p>
      <div className="h2-row__head">
        <h3 className="h2-row__heading">
          <Link
            className="h2-row__title"
            to={resourcePath(card.id, card.title)}
            state={toFromResults(location, pick, card)}
          >
            {card.title}
          </Link>
        </h3>
        <span className="h2-row__save">
          <BookmarkButton card={card} />
        </span>
      </div>
      {pick?.reason && pick.reasonCheck !== 'REMOVED' && (
        <p className="h2-row__reason">
          <AiLabel /> <span {...aiMark()}>{pick.reason}</span>
        </p>
      )}
      <RowText card={card} pick={pick} terms={terms} />
      {card.foundIn && (
        <p className="h2-row__found">
          {card.foundIn.headingPath
            ? t('results.foundIn', {
                list: card.foundIn.listTitle,
                heading: card.foundIn.headingPath,
              })
            : t('results.foundInList', { list: card.foundIn.listTitle })}
        </p>
      )}
      {sources && (
        <p className="h2-row__from">
          {t('results.from', { sources })}
          {staleHere && <span className="h2-row__stale"> · {t('servedFrom.STALE_CACHE')}</span>}
        </p>
      )}
    </li>
  );
}

function RowHost({ url, host }: { url: string; host: string }) {
  const { t } = useTranslation();
  const href = safeHref(url);
  if (!href) return <span className="h2-row__host">{host}</span>;
  return (
    <a className="h2-row__host" href={href} target="_blank" rel="noopener noreferrer nofollow ugc">
      {host} <span aria-hidden="true">↗</span>{' '}
      <span className="sr-only">{t('common.opensNewTab')}</span>
    </a>
  );
}

function RowText({
  card,
  pick,
  terms,
}: {
  card: ResultCard;
  pick: GuidePick | undefined;
  terms: readonly string[];
}) {
  const { t } = useTranslation();
  const text = plainText(card.description ?? '');
  const quote = plainText(pick?.quote ?? '');
  if (text && isAiOrigin(card.descriptionOrigin))
    return (
      <p className="h2-row__text">
        <OriginLabel origin={card.descriptionOrigin} /> <span {...aiMark()}>{text}</span>
      </p>
    );
  if (!quote)
    return text ? <p className="h2-row__text is-clamped">{withSearchTerms(text, terms)}</p> : null;
  const at = locateQuote(text, quote);
  return (
    <div className="h2-row__proof">
      <p className="h2-row__text">
        {at ? (
          <>
            {at.before}
            <mark>{at.match}</mark>
            {at.after}
          </>
        ) : (
          <>
            {text && <>{text} </>}
            <q>
              <mark>{quote}</mark>
            </q>
          </>
        )}
      </p>
      <p className="h2-row__checked">
        <span aria-hidden="true">✓ </span>
        {t('results.checkedQuote')}
      </p>
    </div>
  );
}
