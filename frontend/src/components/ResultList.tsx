import type { KeyboardEvent, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { SelectableRow } from '@/components/ui/SelectableRow';
import { TypeTag } from '@/components/ui/TypeTag';
import { aiMark } from '@/lib/aiMark';
import { formatDate, hostname } from '@/lib/format';
import type { ResultsView } from '@/lib/searchState';
import { creationDate, formatMonth, groupByCreationMonth } from '@/lib/timeline';
import type { GuidePick } from './GuidePicks';

/** The DOM id of a result's row button, so a guide chip or an arrow key can move focus to it. */
export const rowId = (id: string) => `result-${id}`;

/** Where an arrow key, Home or End moves from row [at] of [count]; null for any other key. */
function moveTo(key: string, at: number, count: number): number | null {
  switch (key) {
    case 'ArrowDown': return Math.min(at + 1, count - 1);
    case 'ArrowUp': return Math.max(at - 1, 0);
    case 'Home': return 0;
    case 'End': return count - 1;
    default: return null;
  }
}

/**
 * The result list (ADR 0040, 4): one row per result, in search order; the guide never re-orders it, it adds a
 * "Pick #n" badge and its reason to the picked rows ([picks]). Each row is a button that selects the result
 * ([selected], [onSelect]). One row is in the tab order; the arrow keys, Home and End move between rows and select.
 * [inline] is the preview opened under the selected row up to `bp-split`. The timeline view groups the same rows
 * by the month each result was created, as its source states it (ADR 0010).
 */
export function ResultList({ cards, view, selected, onSelect, picks, inline }: {
  cards: ResultCard[];
  view: ResultsView;
  selected: string | undefined;
  onSelect: (id: string) => void;
  picks: Map<string, GuidePick>;
  inline?: ((card: ResultCard) => ReactNode) | undefined;
}) {
  const { t, i18n } = useTranslation();
  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, at: number) => {
    const to = moveTo(e.key, at, cards.length);
    const card = to === null ? undefined : cards[to];
    if (!card) return;
    e.preventDefault();
    onSelect(card.id);
    document.getElementById(rowId(card.id))?.focus();
  };

  const row = (card: ResultCard) => {
    const at = cards.indexOf(card);
    const pick = picks.get(card.id);
    const isSelected = card.id === selected;
    const created = view === 'timeline' ? creationDate(card) : null;
    const reason = pick && pick.rec.reasonCheck !== 'REMOVED' && pick.rec.reason ? pick.rec.reason : null;
    const id = rowId(card.id);
    return (
      <li key={card.id} className={`result-row${pick ? ' is-pick' : ''}`}>
        {/* Named by its title, then type, site and pick; the description describes it. */}
        <SelectableRow id={id} selected={isSelected} onSelect={() => onSelect(card.id)} tabIndex={isSelected ? 0 : -1}
          onKeyDown={(e) => onKeyDown(e, at)} labelledBy={`${id}-t ${id}-m`} describedBy={card.description ? `${id}-d` : undefined}>
          <span id={`${id}-m`} className="result-row__meta">
            <TypeTag type={card.type} />
            <span className="result-row__domain">{hostname(card.url)}</span>
            {view === 'timeline' && (
              <span className="result-row__created">
                {created
                  ? <>{t('timeline.created')} <time dateTime={created}>{formatDate(created, i18n.language, 'DAY')}</time></>
                  : t('timeline.noDate')}
              </span>
            )}
            {pick && <span className="result-row__pick">{t('picks.badge', { n: pick.n })}</span>}
          </span>
          <span id={`${id}-t`} className="result-row__title">{card.title}</span>
          {card.description && <span id={`${id}-d`} className="result-row__text">{card.description}</span>}
        </SelectableRow>
        {reason && (
          <p className="result-row__reason">
            <span className="result-row__reason-label">{t('picks.reason')}</span> <span {...aiMark(pick?.model)}>{reason}</span>
          </p>
        )}
        {isSelected && inline?.(card)}
      </li>
    );
  };

  if (view === 'list') {
    return <ul className="plain-list result-list" aria-label={t('results.listLabel')}>{cards.map(row)}</ul>;
  }
  return (
    <ol className="plain-list result-list results-timeline" aria-label={t('timeline.label')}>
      {groupByCreationMonth(cards).map((group) => {
        const id = `tl-${group.month ?? 'unknown'}`;
        return (
          <li key={id} className="tl-group">
            <h3 id={id} className="tl-month">
              {group.month ? <time dateTime={group.month}>{formatMonth(group.month, i18n.language)}</time> : t('timeline.unknown')}
            </h3>
            {!group.month && <p className="muted small">{t('timeline.unknownHint')}</p>}
            <ol className="plain-list" aria-labelledby={id}>{group.cards.map(row)}</ol>
          </li>
        );
      })}
    </ol>
  );
}
