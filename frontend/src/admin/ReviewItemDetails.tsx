import './adminText';
import { useTranslation } from 'react-i18next';
import type {
  DraftedExample as Draft,
  ReviewCandidateDetails,
  ReviewItem,
  ReviewKnownTargetProposalDetails,
} from '@/api/types';
import { aiMark } from '@/lib/aiMark';
import { safeHref } from '@/lib/format';
import { CATEGORY_NOTICES, CategoryNotice } from '@/ui/DiscoveryCategories';
import { CandidateEditForm } from './CandidateEditForm';
import { Excerpt } from './Excerpt';
import { KnownTargetProposalForm } from './KnownTargetProposalForm';

export function ItemDetails({ item, onChanged }: { item: ReviewItem; onChanged?: () => void }) {
  const details = item.details;
  const edit =
    item.status === 'OPEN' && onChanged ? { itemId: item.id, onSaved: onChanged } : undefined;
  if (item.draft) return <DraftedExample draft={item.draft} />;
  if (!details) return null;
  if (details.shape === 'CANDIDATE')
    return <ResourceCandidate candidate={details} categories={item.categories ?? []} edit={edit} />;
  if (details.shape === 'KNOWN_TARGET_PROPOSAL')
    return <KnownTargetProposal proposal={details} edit={edit} />;
  return <p className="h2-admin__details">{details.text}</p>;
}

export function candidateOf(item: ReviewItem): ReviewCandidateDetails | null {
  return item.details?.shape === 'CANDIDATE' ? item.details : null;
}

interface Edit {
  itemId: string;
  onSaved: () => void;
}

function KnownTargetProposal({
  proposal,
  edit,
}: {
  proposal: ReviewKnownTargetProposalDetails;
  edit?: Edit | undefined;
}) {
  const { t } = useTranslation();
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
            key={[proposal.kind, proposal.name, ...proposal.otherNames].join('\n')}
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

function DraftedExample({ draft }: { draft: Draft }) {
  const { t } = useTranslation();
  return (
    <dl className="h2-admin__details h2-admin__draft" {...aiMark(draft.model)}>
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
  candidate,
  categories,
  edit,
}: {
  candidate: ReviewCandidateDetails;
  categories: string[];
  edit?: Edit | undefined;
}) {
  const { t } = useTranslation();
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
          <dd>
            <Excerpt key={candidate.url} text={plainText(candidate.snippet)} />
          </dd>
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
            key={candidate.revision}
            itemId={edit.itemId}
            revision={candidate.revision}
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

function plainText(markdown: string): string {
  return markdown
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/(^|\s)#{1,6}\s+/g, '$1')
    .replace(/(\*\*|__|`)/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}
