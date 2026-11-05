import { Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { OriginLabel } from '@/components/Badges';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { displayHost } from '@/lib/format';
import { locateQuote } from '@/lib/locateQuote';
import { resourcePath } from '@/lib/searchState';
import type { GuidePick } from '@/lib/selection';
import { toFromResults } from './fromResults';
import { ResultDate } from './ResultDate';

/** The id a guide pick chip jumps to. */
export const rowAnchor = (id: string) => `result-${id}`;

/**
 * One result in the list (ADR 0042, v4-2): type, host and date, the guide's pick, the title, the description and the
 * sources that list it. The title is a link that covers the whole row and opens the resource page; it passes on where
 * to come back to and, for a pick, the guide's reason and checked quote. A pick shows its reason (AI-written: labelled
 * and marked) and its quote, highlighted in the source's own words. Nothing in the row is selected or opens in place.
 */
export function ResultRow({
  card,
  pick,
  stale,
}: {
  card: ResultCard;
  pick: GuidePick | undefined;
  stale: ReadonlySet<string>;
}) {
  const { t } = useTranslation();
  const location = useLocation();
  const host = displayHost(card.url);
  const sources = card.sources.map((s) => s.sourceName).join(' · ');
  const staleHere = card.sources.some((s) => stale.has(s.sourceId));
  return (
    <li id={rowAnchor(card.id)} data-id={card.id} className={pick ? 'h2-row is-pick' : 'h2-row'}>
      <p className="h2-row__meta">
        <span className="h2-row__type">{t(`type.${card.type}`)}</span>
        <span aria-hidden="true"> · </span>
        <span className="h2-row__host">{host}</span>
        <ResultDate card={card} separator />
        {pick && <span className="h2-row__pick">{t('results.pick', { rank: pick.rank })}</span>}
      </p>
      <Link
        className="h2-row__title"
        to={resourcePath(card.id)}
        state={toFromResults(location, pick)}
      >
        {card.title}
      </Link>
      <RowText card={card} pick={pick} />
      {pick?.reason && pick.reasonCheck !== 'REMOVED' && (
        <p className="h2-row__reason">
          <span className="h2-row__ai">{t('picks.aiWritten')}</span>{' '}
          <span {...aiMark()}>{pick.reason}</span>
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

/**
 * The source's text. A model's description is labelled and marked, and never a quote (ADR 0038). On a pick the
 * checked sentence is highlighted where it stands in that text, or shown on its own when it is not there (it came
 * from the title).
 */
function RowText({ card, pick }: { card: ResultCard; pick: GuidePick | undefined }) {
  const { t } = useTranslation();
  const text = card.description ?? '';
  if (text && isAiOrigin(card.descriptionOrigin))
    return (
      <p className="h2-row__text">
        <span className="h2-row__ai">
          <OriginLabel origin={card.descriptionOrigin} />
        </span>{' '}
        <span {...aiMark()}>{text}</span>
      </p>
    );
  if (!pick?.quote) return text ? <p className="h2-row__text is-clamped">{text}</p> : null;
  const at = locateQuote(text, pick.quote);
  return (
    <>
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
              <mark>{pick.quote}</mark>
            </q>
          </>
        )}
      </p>
      <p className="h2-row__checked">
        <span aria-hidden="true">✓ </span>
        {t('results.checkedQuote')}
      </p>
    </>
  );
}
