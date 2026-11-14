import './adminText';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { KnownTargetKind } from '@/api/types';
import { namesOf } from './KnownTargetsPanel';

const KINDS: KnownTargetKind[] = ['VENDOR', 'SERVICE'];

function errorOf(e: unknown, t: (k: string) => string): string {
  if (e instanceof ApiError) {
    const { code, detail } = e.problem ?? {};
    if (code === 'already-decided') return t('admin.review.proposal.edit.alreadyDecided');
    if (detail && e.status === 400) return detail;
  }
  return t('admin.review.proposal.edit.failed');
}

export function KnownTargetProposalForm({
  itemId,
  kind,
  name,
  otherNames,
  onSaved,
}: {
  itemId: string;
  kind: KnownTargetKind;
  name: string;
  otherNames: string[];
  onSaved: () => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [newKind, setKind] = useState(kind);
  const [newName, setName] = useState(name);
  const [newOthers, setOthers] = useState(otherNames.join('\n'));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await adminApi.editKnownTargetProposal(itemId, {
        kind: newKind,
        name: newName.trim(),
        otherNames: namesOf(newOthers),
      });
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
        {t('admin.review.proposal.edit.open')}
      </button>
    );
  }
  return (
    <form className="h2-admin__form" onSubmit={(e) => void submit(e)}>
      <label>
        {t('admin.settings.targets.kindLabel')}
        <select value={newKind} onChange={(e) => setKind(e.target.value as KnownTargetKind)}>
          {KINDS.map((x) => (
            <option key={x} value={x}>
              {t(`admin.settings.targets.kind.${x}`)}
            </option>
          ))}
        </select>
      </label>
      <label>
        {t('admin.settings.targets.name')}
        <input
          value={newName}
          required
          minLength={2}
          maxLength={60}
          onChange={(e) => setName(e.target.value)}
        />
      </label>
      <label>
        {t('admin.settings.targets.otherNames')}
        <textarea
          rows={3}
          autoComplete="off"
          spellCheck={false}
          value={newOthers}
          onChange={(e) => setOthers(e.target.value)}
        />
      </label>
      <p className="muted small">{t('admin.review.proposal.edit.hint')}</p>
      {error && (
        <p role="alert" className="banner banner-warn">
          {error}
        </p>
      )}
      <div className="h2-admin__tools">
        <button type="submit" className="btn btn-primary" disabled={saving}>
          {saving ? t('admin.review.proposal.edit.saving') : t('admin.review.proposal.edit.save')}
        </button>
        <button type="button" className="btn" onClick={() => setOpen(false)}>
          {t('admin.review.proposal.edit.cancel')}
        </button>
      </div>
    </form>
  );
}
