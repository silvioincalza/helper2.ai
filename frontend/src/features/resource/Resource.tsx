import type { MouseEvent, ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResourceDetail } from '@/api/types';
import { OriginLabel } from '@/ui/Badges';
import { BookmarkButton } from '@/features/saved/BookmarkButton';
import { canGuide } from '@/features/guide/GuideMe';
import { Icon } from '@/ui/Icon';
import { SetupGuidePanel } from '@/features/setup/SetupGuide';
import { DateTimeline } from '@/features/resource/DateTimeline';
import { ProofQuote } from '@/ui/ProofQuote';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { displayHost } from '@/lib/format';
import { CategoryLinks } from './CategoryLinks';
import { fromResults, type CardHead, type PickForPage } from '../search/fromResults';
import { ResultDate } from '../search/ResultDate';
import { AtAGlance } from './AtAGlance';
import './resource.css';
import { OpenButton } from './OpenButton';
import { AiLabel } from '@/ui/AiLabel';

export function cardHeadFor(id: string, state: unknown): CardHead | undefined {
  const { card } = fromResults(state);
  return card?.id === id ? card : undefined;
}

export function Resource({
  head,
  detail,
  children,
}: {
  head: CardHead;
  detail: ResourceDetail | undefined;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const { from, pick } = fromResults(useLocation().state);
  const card = detail?.card;
  return (
    <div className="h2-app h2-res">
      <BackLink from={from} />
      <ResourceHead card={card ?? head} date={card && <ResultDate card={card} separator />}>
        {card && canGuide(card) && <SetupGuidePanel id={card.id} title={card.title} />}
      </ResourceHead>
      {detail ? (
        <ResourceBody detail={detail} pick={pick}>
          {children}
        </ResourceBody>
      ) : (
        <p role="status" aria-busy="true" className="page-loading">
          {t('common.loading')}
        </p>
      )}
    </div>
  );
}

function ResourceBody({
  detail,
  pick,
  children,
}: {
  detail: ResourceDetail;
  pick: PickForPage | undefined;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const { card } = detail;
  const host = displayHost(card.url);
  const aiDescription = !!card.description && isAiOrigin(card.descriptionOrigin);
  const quote = pick?.quote ?? null;
  return (
    <>
      {pick && <WhyPicked pick={pick} />}

      <section className="h2-res__sec" aria-labelledby="h2-res-text">
        <h2 id="h2-res-text" className="h2-res__h">
          {t(aiDescription ? 'results.aboutAi' : 'resource.sourceText')}
        </h2>
        {aiDescription ? (
          <p className="h2-res__text">
            <OriginLabel origin={card.descriptionOrigin} />{' '}
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
        <AtAGlance detail={detail} groupTag="h3" />
      </section>
      <CategoryLinks ids={card.categories} />
      <section className="h2-res__sec" aria-labelledby="h2-res-time">
        <h2 id="h2-res-time" className="h2-res__h">
          {t('results.timeline')}
        </h2>
        <DateTimeline timeline={card.timeline} eventOrigin={card.eventOrigin ?? null} />
      </section>

      <div className="h2-res__details">{children}</div>
    </>
  );
}

function ResourceHead({
  card,
  date,
  children,
}: {
  card: CardHead;
  date?: ReactNode;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const host = displayHost(card.url);
  return (
    <header className="h2-res__head">
      <h1 className="h2-res__title">{card.title}</h1>
      <p className="h2-res__meta">
        <span className="h2-res__type">{t(`type.${card.type}`)}</span> · {host}
        {date}
      </p>
      <div className="h2-res__actions">
        <OpenButton url={card.url} host={host} />
        <BookmarkButton card={card} />
      </div>
      {children}
    </header>
  );
}

const BACK_LABEL: [RegExp, string][] = [
  [/^\/$/, 'resource.back'],
  [/^\/news(\/|$)/, 'resource.backToNews'],
  [/^\/events(\/|$)/, 'resource.backToEvents'],
  [/^\/saved(\/|$)/, 'resource.backToSaved'],
];

function backLabel(from: string | undefined): string {
  if (!from) return 'detail.backToSearch';
  const path = from.split(/[?#]/)[0] ?? '';
  return BACK_LABEL.find(([page]) => page.test(path))?.[1] ?? 'resource.backToResults';
}

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
        {t(backLabel(from))}
      </Link>
    </p>
  );
}

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
            <AiLabel /> <span {...aiMark()}>{pick.reason}</span>
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
          <p className="h2-res__note">
            {t('guide.checksLabel')} <AiLabel>{t('guide.requirementsAi')}</AiLabel>
          </p>
          <ul className="plain-list h2-res__checks" aria-label={t('guide.checksLabel')}>
            {pick.checks.map((c) => (
              <li
                key={c.constraint}
                className={c.status === 'CONFIRMED' ? 'check-ok' : 'check-unknown'}
              >
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
