import { useTranslation } from 'react-i18next';
import type { Assertion, ResourceDetail, ValueOrigin } from '@/api/types';
import { OriginLabel } from '@/components/Badges';
import { Icon } from '@/components/Icon';
import { aiMark, isAiOrigin } from '@/lib/aiMark';

/** The fields every resource page shows (ADR 0056), in this order. */
const FIELDS = [
  'pricing',
  'platforms',
  'useCase',
  'license',
  'openSource',
  'audience',
  'techLevel',
] as const;
type Field = (typeof FIELDS)[number];

/** The card's own name for a field, when it differs (useCase on an assertion, useCases on the card). */
const CARD_FIELD: Partial<Record<Field, string>> = { useCase: 'useCases' };

/** What one field shows: values with their origin, "Sources disagree", or "Not stated". */
type State =
  | { kind: 'values'; values: string[]; origin: ValueOrigin }
  | { kind: 'conflict'; values: string[] }
  | { kind: 'notStated' };

/** A fact the sources state on the card; audience is a Helper2 rule there, not a stated fact. */
function fromCard(field: Field, detail: ResourceDetail): string[] {
  const c = detail.card;
  switch (field) {
    case 'pricing':
      return c.pricing ? [c.pricing] : [];
    case 'platforms':
      return c.platforms;
    case 'useCase':
      return c.useCases;
    case 'license':
      return c.license ? [c.license] : [];
    case 'openSource':
      return c.openSource == null ? [] : [String(c.openSource)];
    case 'techLevel':
      return c.techLevel ? [c.techLevel] : [];
    case 'audience':
      return [];
  }
}

/**
 * The enrichment's values of a field: verified facts first, then an estimate. Unproven facts state nothing, and a
 * rejected estimate (a reviewer's reject or an admin's drop, ADR 0066) states nothing either.
 */
function fromEnrichment(field: Field, assertions: Assertion[]): State {
  const mine = assertions.filter((a) => a.field === field && isAiOrigin(a.origin));
  const conflict = mine.filter((a) => a.verification === 'CONFLICTING');
  if (conflict.length) return { kind: 'conflict', values: conflict.map((a) => a.value) };
  for (const origin of ['AI_EXTRACTED', 'SELF_DECLARED'] as const) {
    const verified = mine.filter((a) => a.origin === origin && a.verification === 'VERIFIED');
    if (verified.length) return { kind: 'values', values: verified.map((a) => a.value), origin };
  }
  const estimated = mine.filter(
    (a) => a.origin === 'AI_ESTIMATED' && a.verification !== 'REJECTED',
  );
  if (estimated.length)
    return { kind: 'values', values: estimated.map((a) => a.value), origin: 'AI_ESTIMATED' };
  return { kind: 'notStated' };
}

/** A value an admin set (ADR 0066): it wins over the sources and the enrichment. */
function fromAdmin(field: Field, assertions: Assertion[]): string[] {
  return assertions
    .filter((a) => a.field === field && a.origin === 'CURATED' && a.verification === 'VERIFIED')
    .map((a) => a.value);
}

function stateOf(field: Field, detail: ResourceDetail): State {
  const curated = fromAdmin(field, detail.assertions);
  if (curated.length) return { kind: 'values', values: curated, origin: 'CURATED' };
  const disagree = detail.card.conflicts.find((c) => c.field === (CARD_FIELD[field] ?? field));
  if (disagree) return { kind: 'conflict', values: disagree.values.map((v) => v.value) };
  const stated = fromCard(field, detail);
  if (stated.length) return { kind: 'values', values: stated, origin: 'SOURCE_PROVIDED' };
  return fromEnrichment(field, detail.assertions);
}

/**
 * Every field of a resource in one of four states (ADR 0056): a value with where it comes from ("Curated by Helper2"
 * when an admin set it, ADR 0066; "From the source",
 * "Self-declared by the vendor", "Extracted by AI", "AI-estimated"), "No", "Sources disagree" or "Not stated". A fact
 * is never guessed: a value the model could not prove with a quote is "Not stated". An estimate (audience, technical
 * level) is labelled and marked as AI-written (EU AI Act Art. 50, ADR 0038).
 */
export function FieldStates({ detail }: { detail: ResourceDetail }) {
  const { t } = useTranslation();
  const word = (field: Field, value: string) => {
    switch (field) {
      case 'pricing':
        return t(`pricing.${value}`, { defaultValue: value });
      case 'useCase':
        return t(`useCase.${value}`, { defaultValue: value });
      case 'techLevel':
        return t(`techLevel.${value}`, { defaultValue: value });
      case 'audience':
        return t(`detail.audience.${value}`, { defaultValue: value });
      case 'openSource':
        return value === 'true' ? t('common.yes') : value === 'false' ? t('common.no') : value;
      default:
        return value;
    }
  };
  return (
    <div className="h2-facts">
      <div className="h2-facts__group">
        <dl>
          {FIELDS.map((field) => {
            const state = stateOf(field, detail);
            return (
              <div key={field} data-field={field}>
                <dt>{t(`detail.field.${field}`)}</dt>
                {state.kind === 'notStated' ? (
                  <dd className="is-not-stated">{t('results.notStated')}</dd>
                ) : state.kind === 'conflict' ? (
                  <dd className="is-conflict">
                    <Icon name="warn" /> {t('date.conflicting')}:{' '}
                    {state.values.map((v) => word(field, v)).join(' · ')}
                  </dd>
                ) : (
                  <dd>
                    <span {...(isAiOrigin(state.origin) ? aiMark() : {})}>
                      {state.values.map((v) => word(field, v)).join(', ')}
                    </span>{' '}
                    {isAiOrigin(state.origin) ? (
                      <span className="h2-row__ai">
                        <OriginLabel origin={state.origin} />
                      </span>
                    ) : (
                      <OriginLabel origin={state.origin} />
                    )}
                  </dd>
                )}
              </div>
            );
          })}
        </dl>
      </div>
    </div>
  );
}
