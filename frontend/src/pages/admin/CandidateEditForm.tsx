import './adminText';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { CandidateEdit, CandidateType } from '@/api/types';

/** The contract's CandidateType values, in its order. */
const TYPES: CandidateType[] = [
  'PRODUCT',
  'AGENT',
  'PROMPT',
  'SKILL',
  'MCP_SERVER',
  'LIBRARY',
  'MODEL',
  'DATASET',
  'ARTICLE',
  'NEWS',
  'EVENT',
];

/** "local-ai, self-hosted" as tags: trimmed, lower case, no empty ones, no repeats. */
export function tagsOf(text: string): string[] {
  return [...new Set(text.split(',').map((t) => t.trim().toLowerCase()))].filter(Boolean);
}

function errorOf(e: unknown, t: (k: string) => string): string {
  if (e instanceof ApiError) {
    const { code, detail } = e.problem ?? {};
    if (code === 'already-decided') return t('admin.candidateEdit.errors.alreadyDecided');
    if (code === 'version-conflict') return t('admin.candidateEdit.errors.changedMeanwhile');
    if (detail && e.status === 400) return detail;
  }
  return t('admin.candidateEdit.errors.failed');
}

/**
 * ADR 0026: an admin fixes an open resource candidate before accepting it: its title, its type (any candidate type) and
 * its tags. Only the fields that changed are sent, with the [details] the admin saw: when the candidate changed since,
 * the server refuses the edit. The item stays open; [onSaved] reloads the list, so the reason line and the details show
 * the new values.
 */
export function CandidateEditForm({
  itemId,
  details,
  title,
  type,
  tags,
  onSaved,
}: {
  itemId: string;
  details: string | null;
  title: string;
  type: string;
  tags: string[];
  onSaved: () => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [newTitle, setTitle] = useState(title);
  const [newType, setType] = useState(type);
  const [newTags, setTags] = useState(tags.join(', '));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const change = (): Omit<CandidateEdit, 'expectedDetails'> => {
    const out: Omit<CandidateEdit, 'expectedDetails'> = {};
    if (newTitle.trim() !== title) out.title = newTitle.trim();
    if (newType !== type) out.type = newType as CandidateType;
    const wanted = tagsOf(newTags);
    if (wanted.slice().sort().join() !== tags.slice().sort().join()) out.tags = wanted;
    return out;
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const body = change();
    if (Object.keys(body).length === 0) {
      setOpen(false);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await adminApi.editCandidate(itemId, { expectedDetails: details, ...body });
      setOpen(false);
      onSaved();
    } catch (err) {
      setError(errorOf(err, t));
    } finally {
      setSaving(false);
    }
  };

  if (!open) {
    return (
      <button type="button" className="btn" onClick={() => setOpen(true)}>
        {t('admin.candidateEdit.open')}
      </button>
    );
  }
  return (
    <form className="h2-admin__form" onSubmit={(e) => void submit(e)}>
      <label>
        {t('admin.candidateEdit.title')}
        <input
          value={newTitle}
          required
          maxLength={300}
          onChange={(e) => setTitle(e.target.value)}
        />
      </label>
      <label>
        {t('admin.candidateEdit.type')}
        <select value={newType} onChange={(e) => setType(e.target.value)}>
          {TYPES.map((x) => (
            <option key={x} value={x}>
              {t(`type.${x}`)}
            </option>
          ))}
        </select>
      </label>
      <label>
        {t('admin.candidateEdit.tags')}
        <input value={newTags} onChange={(e) => setTags(e.target.value)} />
      </label>
      <p className="muted small">{t('admin.candidateEdit.hint')}</p>
      {error && (
        <p role="alert" className="banner banner-warn">
          {error}
        </p>
      )}
      <div className="h2-admin__tools">
        <button type="submit" className="btn btn-primary" disabled={saving}>
          {saving ? t('admin.candidateEdit.saving') : t('admin.candidateEdit.save')}
        </button>
        <button type="button" className="btn" onClick={() => setOpen(false)}>
          {t('admin.candidateEdit.cancel')}
        </button>
      </div>
    </form>
  );
}
