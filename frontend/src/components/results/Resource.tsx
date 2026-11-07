import type { MouseEvent, ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResourceDetail } from '@/api/types';
import { ModeBadge, OriginLabel } from '@/components/Badges';
import { BookmarkButton } from '@/components/BookmarkButton';
import { canGuide } from '@/components/GuideMe';
import { Icon } from '@/components/Icon';
import { SetupGuidePanel } from '@/components/SetupGuide';
import { Timeline } from '@/components/Timeline';
import { ProofQuote } from '@/components/ui/ProofQuote';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { displayHost, formatDate } from '@/lib/format';
import { CategoryLinks } from './CategoryLinks';
import { fromResults, type PickForPage } from './fromResults';
import { ResultDate } from './ResultDate';
import { ResultFacts } from './ResultFacts';
import './resource.css';
import { OpenButton } from './OpenButton';

/**
 * Resource page (ADR 0028 (part was 0042), decision 8): one column. The back link, the title, the date line (type · host · the same date as
 * the result row), one primary button (Open) with Save and, for what you install or run, Set it up. Then why the guide
 * picked it (when it came from a pick), the source's own text with the checked sentence, the facts, its dates, where
 * the information comes from (folded) and the detail sections ([children]).
 */
export function Resource({ detail: d, children }: { detail: ResourceDetail; children: ReactNode }) {
  const { t } = useTranslation();
  const { from, pick } = fromResults(useLocation().state);
  const card = d.card;
  const host = displayHost(card.url);
  // A model wrote it: labelled and marked, never headed "From the source" nor shown as a checked quote (ADR 0018).
  const aiDescription = !!card.description && isAiOrigin(card.descriptionOrigin);
  const quote = pick?.quote ?? null;
  return (
    <div className="v4 h2-res">
      <BackLink from={from} />
      <header className="h2-res__head">
        <h1 className="h2-res__title">{card.title}</h1>
        <p className="h2-res__meta">
          <span className="h2-res__type">{t(`type.${card.type}`)}</span> · {host}
          <ResultDate card={card} separator />
        </p>
        <div className="h2-res__actions">
          <OpenButton url={card.url} host={host} />
          <BookmarkButton card={card} />
        </div>
        {canGuide(card) && <SetupGuidePanel id={card.id} title={card.title} />}
      </header>

      {pick && <WhyPicked pick={pick} />}

      <section className="h2-res__sec" aria-labelledby="h2-res-text">
        <h2 id="h2-res-text" className="h2-res__h">
          {t(aiDescription ? 'results.aboutAi' : 'resource.sourceText')}
        </h2>
        {/* A checked quote can come from the title alone, so it shows even without a description. */}
        {aiDescription ? (
          <p className="h2-res__text">
            <span className="h2-row__ai">
              <OriginLabel origin={card.descriptionOrigin} />
            </span>{' '}
            <span {...aiMark()}>{card.description}</span>
          </p>
        ) : quote ? (
          <ProofQuote text={card.description ?? ''} quote={quote} source={host} />
        ) : card.description ? (
          <p className="h2-res__text">{card.description}</p>
        ) : (
          <p className="muted">{t('card.noDescription')}</p>
        )}
        {quote && !aiDescription && <p className="h2-res__note">{t('resource.checkedNote')}</p>}
      </section>

      <section className="h2-res__sec" aria-labelledby="h2-res-facts">
        <h2 id="h2-res-facts" className="h2-res__h">
          {t('resource.atAGlance')}
        </h2>
        <ResultFacts card={card} groupTag="h3" />
      </section>
      <CategoryLinks ids={card.categories} />
      <section className="h2-res__sec" aria-labelledby="h2-res-time">
        <h2 id="h2-res-time" className="h2-res__h">
          {t('results.timeline')}
        </h2>
        <Timeline timeline={card.timeline} eventOrigin={card.eventOrigin ?? null} />
      </section>

      <WhereFrom card={card} />

      <div className="h2-res__details">{children}</div>
    </div>
  );
}

/**
 * Where this information comes from, folded: each source that lists the resource, how it is searched (live, our index,
 * curated) and when it was read, with a link to the Sources page.
 */
function WhereFrom({ card }: { card: ResourceDetail['card'] }) {
  const { t, i18n } = useTranslation();
  if (card.sources.length === 0) return null;
  return (
    <details className="h2-res__where">
      <summary className="h2-res__fold">
        {t('resource.whereFrom')} <Icon name="chevron" />
      </summary>
      <ul className="plain-list h2-res__sources">
        {card.sources.map((s) => (
          <li key={s.sourceId}>
            <strong>{s.sourceName}</strong> <ModeBadge mode={s.mode} /> ·{' '}
            {t('resource.readAt', { date: formatDate(s.retrievedAt, i18n.language, 'INSTANT') })}
          </li>
        ))}
      </ul>
      <p>
        <Link to="/sources">{t('resource.allSources')}</Link>
      </p>
    </details>
  );
}

/**
 * Back to the results. Opened from a row, it goes back in history, so the list is where the reader left it (the
 * browser keeps the scroll on Back); a new tab or a shared link has no history here and follows the link instead.
 */
function BackLink({ from }: { from: string | undefined }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { key } = useLocation();
  const onClick = (e: MouseEvent<HTMLAnchorElement>) => {
    const plain = e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey;
    if (!from || key === 'default' || !plain) return;
    e.preventDefault();
    void navigate(-1);
  };
  return (
    <p className="h2-res__back">
      <Link to={from ?? '/'} onClick={onClick}>
        <span aria-hidden="true">← </span>
        {from ? t('resource.backToResults') : t('detail.backToSearch')}
      </Link>
    </p>
  );
}

/**
 * Why the guide picked it, when the page was opened from a pick: the rank, the reason (AI-written: labelled and
 * marked, ADR 0018) with what the second model call made of it, and the requirements checked against the source.
 * A reason that call removed is not shown; the line says why.
 */
function WhyPicked({ pick }: { pick: PickForPage }) {
  const { t } = useTranslation();
  const removed = pick.reasonCheck === 'REMOVED' || !pick.reason;
  return (
    <section className="h2-res__sec h2-res__pick" aria-labelledby="h2-res-why">
      <h2 id="h2-res-why" className="h2-res__h">
        {t('results.whyPicked')}{' '}
        <span className="h2-res__rank">{t('results.pick', { rank: pick.rank })}</span>
      </h2>
      {removed ? (
        <p className="h2-res__note">
          {t(pick.reasonCheck === 'REMOVED' ? 'guide.reasonRemoved' : 'results.noReason')}
        </p>
      ) : (
        <>
          <p className="h2-res__reason">
            <span className="h2-row__ai">{t('picks.aiWritten')}</span>{' '}
            <span {...aiMark()}>{pick.reason}</span>
          </p>
          <p className="h2-res__note">
            {t(
              pick.reasonCheck === 'SUPPORTED'
                ? 'results.reasonChecked'
                : 'results.reasonNotChecked',
            )}
          </p>
        </>
      )}
      {pick.checks.length > 0 && (
        <>
          {/* Art. 50: the planner wrote the requirements; labelled on screen once, marked on each. */}
          <p className="h2-res__note">
            {t('guide.checksLabel')} <span className="h2-row__ai">{t('guide.requirementsAi')}</span>
          </p>
          <ul className="plain-list h2-res__checks" aria-label={t('guide.checksLabel')}>
            {pick.checks.map((c) => (
              <li
                key={c.constraint}
                className={c.status === 'CONFIRMED' ? 'check-ok' : 'check-unknown'}
              >
                {/* Status in words, never colour alone (WCAG 1.4.1). */}
                <Icon name={c.status === 'CONFIRMED' ? 'check' : 'info'} />{' '}
                <strong {...aiMark()}>{c.constraint}</strong>:{' '}
                {c.status === 'CONFIRMED' ? (
                  <>
                    {t('guide.checkConfirmed')} <q>{c.quote}</q>
                  </>
                ) : (
                  t('guide.checkNotConfirmed')
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
