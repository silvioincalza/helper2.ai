import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError, problemDetail } from '@/api/client';
import type { ReviewItem, ReviewResearchStart } from '@/api/types';
import { bulkSummary, type Decision } from './ReviewBulk';
import type { ResearchOutcome } from './ResearchDecisions';
import { NOTE_MAX } from './limits';
import { candidateOf } from './ReviewItemDetails';

type ActionError = { kind: 'failed' | 'alreadyDecided' } | { kind: 'refused'; detail: string };

function actionErrorOf(e: unknown): ActionError {
  const detail = problemDetail(e, { code: ['duplicate', 'invalid-request'] });
  if (detail) return { kind: 'refused', detail };
  if (e instanceof ApiError && e.status === 409) return { kind: 'alreadyDecided' };
  return { kind: 'failed' };
}

interface ReviewActionsContext {
  total: number | undefined;
  reloadQueue: () => void;
  reloadResearch: () => void;
  researched: ResearchOutcome | null;
  pickedIds: () => string[];
  keepFailedPicks: (sent: string[], failed: ReadonlySet<string>) => void;
  onResolved: (item: ReviewItem) => void;
  onResolvedMany: () => void;
}

export function useReviewActions({
  total,
  reloadQueue,
  reloadResearch,
  researched,
  pickedIds,
  keepFailedPicks,
  onResolved,
  onResolvedMany,
}: ReviewActionsContext) {
  const { t } = useTranslation();
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [error, setError] = useState<ActionError | null>(null);
  const [listed, setListed] = useState<ReviewItem['alreadyListed'] | null>(null);
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const [bulkResult, setBulkResult] = useState('');
  const [startingAll, setStartingAll] = useState(false);
  const [rejecting, setRejecting] = useState<ReviewItem[]>([]);
  const acceptBlocked = !!researched && !researched.complete;

  const begin = () => {
    if (inFlight.current) return false;
    inFlight.current = true;
    setBusy(true);
    return true;
  };
  const end = () => {
    inFlight.current = false;
    setBusy(false);
  };

  const noteOf = (id: string) => notes[id] ?? '';
  const setNote = (id: string, text: string) => setNotes((n) => ({ ...n, [id]: text }));

  const resolve = async (item: ReviewItem, decision: Decision) => {
    if (inFlight.current || item.status !== 'OPEN') return;
    if (decision === 'ACCEPTED' && acceptBlocked) {
      setAnnouncement(t('admin.research.acceptWhy', { count: researched?.left.length ?? 0 }));
      return;
    }
    const edit = decision === 'ACCEPTED' ? researched?.edit : null;
    const revision = candidateOf(item)?.revision;
    if (edit && !revision) {
      setError({ kind: 'refused', detail: t('admin.research.unreadable') });
      return;
    }
    if (!begin()) return;
    setError(null);
    setListed(null);
    try {
      if (edit && revision) {
        await adminApi.editCandidate(item.id, { expectedRevision: revision, ...edit });
      }
      const text = (notes[item.id]?.trim() ?? '').slice(0, NOTE_MAX);
      const decided = await adminApi.resolveReviewItem(item.id, {
        status: decision,
        ...(text ? { note: text } : {}),
      });
      setListed(decided.alreadyListed ?? null);
      setNotes(({ [item.id]: _, ...rest }) => rest);
      const left = Math.max(0, (total ?? 1) - 1);
      setAnnouncement(t(`admin.review.decided.${decision}`, { count: left }));
      onResolved(item);
      reloadQueue();
    } catch (e) {
      const failure = actionErrorOf(e);
      setError(failure);
      if (failure.kind === 'alreadyDecided') reloadQueue();
    } finally {
      end();
    }
  };

  const resolveMany = async (
    decision: Decision,
    text: string | undefined,
    ids: string[] = pickedIds(),
  ) => {
    if (ids.length === 0 || !begin()) return;
    setError(null);
    try {
      const result = await adminApi.resolveReviewItems({
        ids,
        status: decision,
        ...(text ? { note: text } : {}),
      });
      const failed = new Set(result.items.filter((i) => i.outcome !== 'RESOLVED').map((i) => i.id));
      keepFailedPicks(ids, failed);
      setRejecting([]);
      setBulkResult(bulkSummary(t, result));
      onResolvedMany();
      reloadQueue();
    } catch {
      setError({ kind: 'failed' });
      reloadQueue();
    } finally {
      end();
    }
  };

  const startResearch = async (body: ReviewResearchStart) => {
    if (!begin()) return;
    setError(null);
    try {
      const started = await adminApi.startReviewResearch(body);
      setAnnouncement(t('admin.research.queued', { count: started.runs.length }));
      setStartingAll(false);
      reloadResearch();
    } catch (e) {
      setError(actionErrorOf(e));
    } finally {
      end();
    }
  };

  const cancelResearch = async (runId: string) => {
    try {
      await adminApi.cancelReviewResearch(runId);
      setAnnouncement(t('admin.research.cancelled'));
    } catch (e) {
      setError(actionErrorOf(e));
    } finally {
      reloadResearch();
    }
  };

  return {
    busy,
    error,
    listed,
    announcement,
    bulkResult,
    acceptBlocked,
    noteOf,
    setNote,
    resolve,
    resolveMany,
    startResearch,
    cancelResearch,
    startingAll,
    setStartingAll,
    rejecting,
    setRejecting,
  };
}
