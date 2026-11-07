import './adminText';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { api, ApiError } from '@/api/client';
import type { NewResource, RelationshipTargetChange, ResultCard } from '@/api/types';

type Mode = 'existing' | 'added' | 'renamed';
type NewType = NewResource['type'];

/** The types a relationship can point at; the contract's NewResource types, in its order. */
const TYPES: NewType[] = [
  'PRODUCT',
  'MODEL',
  'AGENT',
  'PROMPT',
  'SKILL',
  'MCP_SERVER',
  'LIBRARY',
  'DATASET',
];

/** A refusal the server explained, or a fault. */
function errorOf(e: unknown, t: (k: string) => string): string {
  if (e instanceof ApiError) {
    const { code, detail } = e.problem ?? {};
    if (code === 'already-decided') return t('admin.target.errors.alreadyDecided');
    if (detail && (e.status === 400 || e.status === 409)) return detail;
  }
  return t('admin.target.errors.failed');
}

/**
 * ADR 0026: an admin points an open relationship candidate at the right resource: one found in the catalog, one added
 * now (name, official https address, type), or no resource under another name. The item stays open; [onChanged]
 * gets nothing back but reloads the evidence. Closed until asked for, so the evidence stays the first thing shown.
 */
export function RelationshipTarget({
  itemId,
  currentTitle,
  onChanged,
}: {
  itemId: string;
  currentTitle: string;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<Mode>('existing');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async (change: RelationshipTargetChange) => {
    setSaving(true);
    setError(null);
    try {
      await adminApi.changeRelationshipTarget(itemId, change);
      setOpen(false);
      onChanged();
    } catch (e) {
      setError(errorOf(e, t));
    } finally {
      setSaving(false);
    }
  };

  if (!open) {
    return (
      <button type="button" className="btn" onClick={() => setOpen(true)}>
        {t('admin.target.change')}
      </button>
    );
  }
  return (
    <div className="h2-admin__form">
      <fieldset>
        <legend>{t('admin.target.legend')}</legend>
        {(['existing', 'added', 'renamed'] as const).map((m) => (
          <label key={m} className="h2-admin__choice">
            <input
              type="radio"
              name={`target-mode-${itemId}`}
              checked={mode === m}
              onChange={() => setMode(m)}
            />{' '}
            {t(`admin.target.modes.${m}`)}
          </label>
        ))}
      </fieldset>
      {mode === 'existing' && (
        <ExistingPicker
          initial={currentTitle}
          saving={saving}
          onPick={(id) => void save({ resourceId: id })}
        />
      )}
      {mode === 'added' && (
        <AddForm
          initial={currentTitle}
          saving={saving}
          onAdd={(r) => void save({ newResource: r })}
        />
      )}
      {mode === 'renamed' && (
        <RenameForm
          initial={currentTitle}
          saving={saving}
          onRename={(title) => void save({ title })}
        />
      )}
      {error && (
        <p role="alert" className="banner banner-warn">
          {error}
        </p>
      )}
      <p className="muted small">{t('admin.target.hint')}</p>
      <button type="button" className="btn" onClick={() => setOpen(false)}>
        {t('admin.target.cancel')}
      </button>
    </div>
  );
}

/** A plain catalog search (the one visitors get), as in the home examples' picker. */
function ExistingPicker({
  initial,
  saving,
  onPick,
}: {
  initial: string;
  saving: boolean;
  onPick: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [q, setQ] = useState(initial);
  const [found, setFound] = useState<ResultCard[] | null>(null);
  const find = async () => {
    const words = q.trim();
    if (!words) return;
    try {
      const res = await api.search(new URLSearchParams({ q: words, size: '5' }));
      setFound(res.results);
    } catch {
      setFound([]);
    }
  };
  return (
    <>
      <div className="h2-admin__tools">
        <label>
          {t('admin.target.find')}
          <input
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void find();
              }
            }}
          />
        </label>
        <button type="button" className="btn" onClick={() => void find()}>
          {t('admin.target.findButton')}
        </button>
      </div>
      {found && found.length === 0 && <p className="muted">{t('admin.target.none')}</p>}
      {found && found.length > 0 && (
        <ul className="plain-list">
          {found.map((r) => (
            <li key={r.id} className="h2-admin__found">
              <span>
                {r.title} <span className="muted">· {t(`type.${r.type}`)}</span>
              </span>
              <button type="button" className="btn" disabled={saving} onClick={() => onPick(r.id)}>
                {t('admin.target.use')} <span className="sr-only">{r.title}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function AddForm({
  initial,
  saving,
  onAdd,
}: {
  initial: string;
  saving: boolean;
  onAdd: (r: NewResource) => void;
}) {
  const { t } = useTranslation();
  const [title, setTitle] = useState(initial);
  const [url, setUrl] = useState('https://');
  const [type, setType] = useState<NewType>('MODEL');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onAdd({ title: title.trim(), url: url.trim(), type });
  };
  return (
    <form className="h2-admin__form" onSubmit={submit}>
      <label>
        {t('admin.target.name')}
        <input value={title} required maxLength={300} onChange={(e) => setTitle(e.target.value)} />
      </label>
      <label>
        {t('admin.target.url')}
        <input
          type="url"
          value={url}
          required
          maxLength={2000}
          pattern="https://.+"
          onChange={(e) => setUrl(e.target.value)}
        />
      </label>
      <label>
        {t('admin.target.type')}
        <select value={type} onChange={(e) => setType(e.target.value as NewType)}>
          {TYPES.map((x) => (
            <option key={x} value={x}>
              {t(`type.${x}`)}
            </option>
          ))}
        </select>
      </label>
      <SaveButton saving={saving} />
    </form>
  );
}

function RenameForm({
  initial,
  saving,
  onRename,
}: {
  initial: string;
  saving: boolean;
  onRename: (title: string) => void;
}) {
  const { t } = useTranslation();
  const [title, setTitle] = useState(initial);
  return (
    <form
      className="h2-admin__form"
      onSubmit={(e) => {
        e.preventDefault();
        onRename(title.trim());
      }}
    >
      <label>
        {t('admin.target.name')}
        <input value={title} required maxLength={120} onChange={(e) => setTitle(e.target.value)} />
      </label>
      <SaveButton saving={saving} />
    </form>
  );
}

function SaveButton({ saving }: { saving: boolean }) {
  const { t } = useTranslation();
  return (
    <button type="submit" className="btn btn-primary" disabled={saving}>
      {saving ? t('admin.target.saving') : t('admin.target.save')}
    </button>
  );
}
