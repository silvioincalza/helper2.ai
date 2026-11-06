import { useTranslation } from 'react-i18next';
import type { ReviewItem } from '@/api/types';
import { CATEGORY_NOTICES, CategoryNotice } from '@/components/sources/DiscoveryCategories';

/** What an item says by its kind: a drafted example, a resource candidate, or its details as plain text. */
export function ItemDetails({ item }: { item: ReviewItem }) {
  if (item.kind === 'EXAMPLE_CANDIDATE') return <DraftedExample details={item.details} />;
  if (item.kind === 'RESOURCE_CANDIDATE')
    return <ResourceCandidate details={item.details} categories={item.categories ?? []} />;
  return item.details ? <p className="h2-admin__details">{item.details}</p> : null;
}

/** A drafted example's details are JSON (label, query, path, model): shown as words, marked as AI-drafted. */
function DraftedExample({ details }: { details: string | null | undefined }) {
  const { t } = useTranslation();
  const draft = parseDraft(details);
  if (!draft) return details ? <p className="h2-admin__details">{details}</p> : null;
  return (
    <dl className="h2-admin__details h2-admin__draft" data-ai-generated="true">
      <dt>{t('admin.review.drafted.label')}</dt>
      <dd>{draft.label}</dd>
      <dt>{t('admin.review.drafted.query')}</dt>
      <dd>{draft.query}</dd>
      <dt>{t('admin.review.drafted.path')}</dt>
      <dd>{t(`admin.examples.path.${draft.path}`)}</dd>
      <dd className="h2-admin__draft-note">
        {t('admin.review.drafted.model', { model: draft.model })}
      </dd>
    </dl>
  );
}

interface Draft {
  label: string;
  query: string;
  path: 'USE_AI' | 'BUILD_WITH_AI';
  model: string;
}

function parseDraft(details: string | null | undefined): Draft | null {
  if (!details) return null;
  try {
    const d = JSON.parse(details) as Partial<Draft>;
    return typeof d.label === 'string' &&
      typeof d.query === 'string' &&
      (d.path === 'USE_AI' || d.path === 'BUILD_WITH_AI') &&
      typeof d.model === 'string'
      ? (d as Draft)
      : null;
  } catch {
    return null;
  }
}

/**
 * A product page a discovery category found (ADR 0051): the details are the candidate as JSON. Every collector that
 * found the page is named; health and legal candidates carry their notice.
 */
function ResourceCandidate({
  details,
  categories,
}: {
  details: string | null | undefined;
  categories: string[];
}) {
  const { t } = useTranslation();
  const candidate = parseCandidate(details);
  if (!candidate) return details ? <p className="h2-admin__details">{details}</p> : null;
  const notices = categories.filter((c) => c in CATEGORY_NOTICES);
  return (
    <dl className="h2-admin__details h2-admin__draft">
      <dt>{t('admin.review.candidate.page')}</dt>
      <dd>
        <a href={candidate.url} target="_blank" rel="noopener noreferrer nofollow">
          {candidate.title}
        </a>{' '}
        <span className="muted">{candidate.url}</span>
      </dd>
      <dt>{t('admin.review.candidate.type')}</dt>
      <dd>{t(`type.${candidate.type}`, { defaultValue: candidate.type })}</dd>
      {candidate.tags.length > 0 && (
        <>
          <dt>{t('admin.review.candidate.tags')}</dt>
          <dd>{candidate.tags.join(', ')}</dd>
        </>
      )}
      {candidate.foundBy.length > 0 && (
        <>
          <dt>{t('admin.review.candidate.foundBy')}</dt>
          <dd>{candidate.foundBy.join(', ')}</dd>
        </>
      )}
      {candidate.snippet && (
        <>
          <dt>{t('admin.review.candidate.excerpt')}</dt>
          <dd>{candidate.snippet}</dd>
        </>
      )}
      {notices.map((c) => (
        <dd key={c}>
          <CategoryNotice category={c} />
        </dd>
      ))}
      <dd className="h2-admin__draft-note">{t('admin.review.candidate.note')}</dd>
    </dl>
  );
}

interface Candidate {
  url: string;
  title: string;
  type: string;
  tags: string[];
  snippet: string | undefined;
  /** The collectors that found it (version 2); version 1 names only `sourceId`. */
  foundBy: string[];
}

const textsOf = (v: unknown): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];

/** The source ids in a version 2 `foundBy` ({sourceId, discovery}); else the one `sourceId`. */
function findersOf(d: { foundBy?: unknown; sourceId?: unknown }): string[] {
  const ids = Array.isArray(d.foundBy)
    ? d.foundBy
        .map((f: unknown) =>
          f && typeof f === 'object' ? (f as { sourceId?: unknown }).sourceId : undefined,
        )
        .filter((x): x is string => typeof x === 'string')
    : [];
  if (ids.length > 0) return ids;
  return typeof d.sourceId === 'string' ? [d.sourceId] : [];
}

/** The candidate, or null when the details are not one; only https links are shown as links. */
function parseCandidate(details: string | null | undefined): Candidate | null {
  if (!details) return null;
  try {
    const d = JSON.parse(details) as Partial<Record<keyof Candidate | 'sourceId', unknown>>;
    return typeof d.url === 'string' &&
      d.url.startsWith('https://') &&
      typeof d.title === 'string' &&
      typeof d.type === 'string'
      ? {
          url: d.url,
          title: d.title,
          type: d.type,
          tags: textsOf(d.tags),
          snippet: typeof d.snippet === 'string' ? d.snippet : undefined,
          foundBy: findersOf(d),
        }
      : null;
  } catch {
    return null;
  }
}
