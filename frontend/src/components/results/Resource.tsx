import type { MouseEvent, ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResourceDetail } from '@/api/types';
import { OriginLabel } from '@/components/Badges';
import { BookmarkButton } from '@/components/BookmarkButton';
import { canGuide } from '@/components/GuideMe';
import { Icon } from '@/components/Icon';
import { SetupGuidePanel } from '@/components/SetupGuide';
import { Timeline } from '@/components/Timeline';
import { ProofQuote } from '@/components/ui/ProofQuote';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { displayHost } from '@/lib/format';
import type { GuidePick } from '@/lib/selection';
import { fromResults } from './fromResults';
import { ListedBy, ResultFacts } from './ResultFacts';
import './resource.css';
import { OpenButton } from './OpenButton';

/**
 * Resource page (ADR 0042, v4-2): one column. The back link, the type, host and who lists it, the title, one primary
 * button (Open) with Save and, for what you install or run, Set it up. Then why the guide picked it (when it came from
 * a pick), the source's own text with the checked sentence, the facts, who lists it, its dates and the detail
 * sections ([children]).
 */
export function Resource({ detail: d, children }: { detail: ResourceDetail; children: ReactNode }) {
  const { t } = useTranslation();
  const { from, pick } = fromResults(useLocation().state);
  const card = d.card;
  const host = displayHost(card.url);
  const sources = card.sources.map((s) => s.sourceName).join(', ');
  // A model wrote it: labelled and marked, never headed "From the source" nor shown as a checked quote (ADR 0038).
  const aiDescription = !!card.description && isAiOrigin(card.descriptionOrigin);
  const quote = pick?.quote ?? null;
  return (
    <div className="v4 h2-res">
      <BackLink from={from} />
      <header className="h2-res__head">
        <p className="h2-res__meta">
          <span className="h2-res__type">{t(`type.${card.type}`)}</span> · {host}
          {sources && <> · {t('resource.listedBy', { sources })}</>}
        </p>
        <h1 className="h2-res__title">{card.title}</h1>
        <div className="h2-res__actions">
          <OpenButton url={card.url} host={host} />
          <BookmarkButton card={card} />
        </div>
        {canGuide(card) && <SetupGuidePanel id={card.id} title={card.title} />}
      </header>

      {pick && <WhyPicked pick={pick} />}

      <section className="h2-res__sec" aria-labelledby="h2-res-text">
        <h2 id="h2-res-text" className="h2-res__h">
          {t(aiDescription ? 'results.about' : 'resource.sourceText')}
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
      <section className="h2-res__sec" aria-labelledby="h2-res-listed">
        <h2 id="h2-res-listed" className="h2-res__h">
          {t('results.listedBy')}
        </h2>
        <ListedBy card={card} />
      </section>
      <section className="h2-res__sec" aria-labelledby="h2-res-time">
        <h2 id="h2-res-time" className="h2-res__h">
          {t('results.timeline')}
        </h2>
        <Timeline timeline={card.timeline} />
      </section>

      <div className="h2-res__details">{children}</div>
    </div>
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
 * marked, ADR 0038) with what the second model call made of it, and the requirements checked against the source.
 * A reason that call removed is not shown; the line says why.
 */
function WhyPicked({ pick }: { pick: GuidePick }) {
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
