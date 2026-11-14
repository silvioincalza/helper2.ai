import './adminText';
import { useTranslation } from 'react-i18next';
import type { DraftedExample as Draft, ReviewItem } from '@/api/types';
import { safeHref } from '@/lib/format';
import { CATEGORY_NOTICES, CategoryNotice } from '@/features/sources/DiscoveryCategories';
import { CandidateEditForm } from './CandidateEditForm';
import { KnownTargetProposalForm } from './KnownTargetProposalForm';

export function ItemDetails({ item, onChanged }: { item: ReviewItem; onChanged?: () => void }) {
  if (item.kind === 'EXAMPLE_CANDIDATE')
    return <DraftedExample draft={item.draft} details={item.details} />;
  if (item.kind === 'RESOURCE_CANDIDATE')
    return (
      <ResourceCandidate
        details={item.details}
        categories={item.categories ?? []}
        edit={
          item.status === 'OPEN' && onChanged ? { itemId: item.id, onSaved: onChanged } : undefined
        }
      />
    );
  if (item.kind === 'KNOWN_TARGET_CANDIDATE')
    return (
      <KnownTargetProposal
        details={item.details}
        edit={
          item.status === 'OPEN' && onChanged ? { itemId: item.id, onSaved: onChanged } : undefined
        }
      />
    );
  return item.details ? <p className="h2-admin__details">{item.details}</p> : null;
}

interface Proposal {
  name: string;
  kind: 'VENDOR' | 'SERVICE';
  rule: 'OWNER';
  count: number;
  examples: string[];
  otherNames: string[];
  edited: boolean;
}

function parseProposal(details: string | null | undefined): Proposal | null {
  if (!details) return null;
  try {
    const d = JSON.parse(details) as Record<string, unknown>;
    return typeof d.name === 'string' &&
      (d.kind === 'VENDOR' || d.kind === 'SERVICE') &&
      d.rule === 'OWNER' &&
      typeof d.count === 'number'
      ? {
          name: d.name,
          kind: d.kind,
          rule: d.rule,
          count: d.count,
          examples: Array.isArray(d.examples)
            ? d.examples
                .map((e: unknown) =>
                  e && typeof e === 'object' ? (e as { title?: unknown }).title : undefined,
                )
                .filter((x): x is string => typeof x === 'string')
            : [],
          otherNames: textsOf(d.otherNames),
          edited: d.edited === true,
        }
      : null;
  } catch {
    return null;
  }
}

function KnownTargetProposal({
  details,
  edit,
}: {
  details: string | null | undefined;
  edit?: { itemId: string; onSaved: () => void } | undefined;
}) {
  const { t } = useTranslation();
  const proposal = parseProposal(details);
  if (!proposal) return details ? <p className="h2-admin__details">{details}</p> : null;
  return (
    <dl className="h2-admin__details h2-admin__draft">
      <dt>{t('admin.review.proposal.name')}</dt>
      <dd>{proposal.name}</dd>
      <dt>{t('admin.review.proposal.kind')}</dt>
      <dd>{t(`admin.settings.targets.kind.${proposal.kind}`)}</dd>
      {proposal.otherNames.length > 0 && (
        <>
          <dt>{t('admin.review.proposal.otherNames')}</dt>
          <dd>{proposal.otherNames.join(', ')}</dd>
        </>
      )}
      <dt>{t('admin.review.proposal.foundBy')}</dt>
      <dd>{t(`admin.review.proposal.rule.${proposal.rule}`, { count: proposal.count })}</dd>
      {proposal.examples.length > 0 && (
        <>
          <dt>{t('admin.review.proposal.examples')}</dt>
          <dd>{proposal.examples.join(', ')}</dd>
        </>
      )}
      {proposal.edited && (
        <dd className="h2-admin__draft-note">{t('admin.review.proposal.edited')}</dd>
      )}
      <dd className="h2-admin__draft-note">{t('admin.review.proposal.note')}</dd>
      {edit && (
        <dd>
          <KnownTargetProposalForm
            key={details ?? ''}
            itemId={edit.itemId}
            kind={proposal.kind}
            name={proposal.name}
            otherNames={proposal.otherNames}
            onSaved={edit.onSaved}
          />
        </dd>
      )}
    </dl>
  );
}

function DraftedExample({
  draft,
  details,
}: {
  draft: Draft | null | undefined;
  details: string | null | undefined;
}) {
  const { t } = useTranslation();
  if (!draft) return details ? <p className="h2-admin__details">{details}</p> : null;
  return (
    <dl
      className="h2-admin__details h2-admin__draft"
      data-ai-generated="true"
      data-ai-model={draft.model}
    >
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

function ResourceCandidate({
  details,
  categories,
  edit,
}: {
  details: string | null | undefined;
  categories: string[];
  edit?: { itemId: string; onSaved: () => void } | undefined;
}) {
  const { t } = useTranslation();
  const candidate = parseCandidate(details);
  if (!candidate) return details ? <p className="h2-admin__details">{details}</p> : null;
  const notices = categories.filter((c) => c in CATEGORY_NOTICES);
  return (
    <dl className="h2-admin__details h2-admin__draft">
      <dt>{t('admin.review.candidate.page')}</dt>
      <dd>
        <a href={safeHref(candidate.url)} target="_blank" rel="noopener noreferrer nofollow">
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
      {candidate.edited.length > 0 && (
        <dd className="h2-admin__draft-note">
          {t('admin.review.candidate.edited', {
            fields: candidate.edited.map((f) => t(`admin.candidateEdit.fields.${f}`)).join(', '),
          })}
        </dd>
      )}
      <dd className="h2-admin__draft-note">{t('admin.review.candidate.note')}</dd>
      {edit && (
        <dd>
          <CandidateEditForm
            key={details ?? ''}
            itemId={edit.itemId}
            details={details ?? null}
            title={candidate.title}
            type={candidate.type}
            tags={candidate.tags}
            onSaved={edit.onSaved}
          />
        </dd>
      )}
    </dl>
  );
}

interface Candidate {
  url: string;
  title: string;
  type: string;
  tags: string[];
  snippet: string | undefined;
  foundBy: string[];
  edited: string[];
}

const textsOf = (v: unknown): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];

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
          edited: textsOf((d as { edited?: unknown }).edited),
        }
      : null;
  } catch {
    return null;
  }
}
