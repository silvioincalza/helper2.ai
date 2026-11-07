import './adminText';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { ResourceField } from '@/api/types';
import { isAiOrigin } from '@/lib/aiMark';

/** The values an admin may set per field (ADR 0026); license is free text (an SPDX id). */
const CHOICES: Partial<Record<ResourceField, readonly string[]>> = {
  type: [
    'PRODUCT',
    'MODEL',
    'AGENT',
    'PROMPT',
    'SKILL',
    'MCP_SERVER',
    'LIBRARY',
    'DATASET',
    'ARTICLE',
    'PAPER',
    'NEWS',
    'EVENT',
    'COURSE',
  ],
  pricing: ['FREE', 'FREEMIUM', 'PAID', 'OPEN_SOURCE_SELF_HOSTED'],
  openSource: ['true', 'false'],
  techLevel: ['BEGINNER', 'INTERMEDIATE', 'ADVANCED'],
  audience: ['USE_AI', 'BUILD_WITH_AI', 'BOTH'],
};

const SETTABLE: readonly string[] = [
  'type',
  'pricing',
  'license',
  'openSource',
  'techLevel',
  'audience',
];
const DROPPABLE: readonly string[] = [...SETTABLE, 'useCase', 'platforms'];

/** A value's words: the same ones the resource page uses. */
function useWord() {
  const { t } = useTranslation();
  return (field: string, value: string) => {
    switch (field) {
      case 'type':
        return t(`type.${value}`, { defaultValue: value });
      case 'pricing':
        return t(`pricing.${value}`, { defaultValue: value });
      case 'techLevel':
        return t(`techLevel.${value}`, { defaultValue: value });
      case 'audience':
        return t(`detail.audience.${value}`, { defaultValue: value });
      case 'openSource':
        return value === 'true' ? t('common.yes') : t('common.no');
      default:
        return value;
    }
  };
}

function errorOf(e: unknown, t: (k: string) => string): string {
  if (e instanceof ApiError && e.status === 400 && e.problem?.detail) return e.problem.detail;
  return t('admin.fieldCuration.failed');
}

/**
 * ADR 0026: next to a field of an enrichment check, the admin sets the resource's own value (it wins over every source
 * and every run) or drops what the AI said. Both reload the evidence through [onChanged].
 */
export function FieldCuration({
  resourceId,
  field,
  value,
  origin,
  onChanged,
}: {
  resourceId: string;
  field: string;
  value: string;
  origin: string;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const word = useWord();
  const [open, setOpen] = useState(false);
  const choices = CHOICES[field as ResourceField];
  const [chosen, setChosen] = useState(choices?.includes(value) ? value : (choices?.[0] ?? value));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const settable = SETTABLE.includes(field);
  const droppable = DROPPABLE.includes(field) && isAiOrigin(origin);
  if (!settable && !droppable) return null;

  const run = async (call: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await call();
      setOpen(false);
      onChanged();
    } catch (e) {
      setError(errorOf(e, t));
    } finally {
      setBusy(false);
    }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    void run(() =>
      adminApi.setResourceField(resourceId, field as ResourceField, { value: chosen.trim() }),
    );
  };
  const label = t(`detail.field.${field}`, { defaultValue: field });

  return (
    <div className="h2-admin__tools">
      {settable && !open && (
        <button type="button" className="btn" onClick={() => setOpen(true)}>
          {t('admin.fieldCuration.set')} <span className="sr-only">{label}</span>
        </button>
      )}
      {droppable && !open && (
        <button
          type="button"
          className="btn"
          disabled={busy}
          onClick={() =>
            void run(() => adminApi.dropResourceField(resourceId, field as ResourceField))
          }
        >
          {t('admin.fieldCuration.drop')} <span className="sr-only">{label}</span>
        </button>
      )}
      {open && (
        <form className="h2-admin__form" onSubmit={submit}>
          <label>
            {t('admin.fieldCuration.value', { field: label })}
            {choices ? (
              <select value={chosen} onChange={(e) => setChosen(e.target.value)}>
                {choices.map((c) => (
                  <option key={c} value={c}>
                    {word(field, c)}
                  </option>
                ))}
              </select>
            ) : (
              <input
                value={chosen}
                required
                maxLength={40}
                onChange={(e) => setChosen(e.target.value)}
              />
            )}
          </label>
          <p className="muted small">{t('admin.fieldCuration.hint')}</p>
          <div className="h2-admin__tools">
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? t('admin.fieldCuration.saving') : t('admin.fieldCuration.save')}
            </button>
            <button type="button" className="btn" onClick={() => setOpen(false)}>
              {t('admin.fieldCuration.cancel')}
            </button>
          </div>
        </form>
      )}
      {error && (
        <p role="alert" className="banner banner-warn">
          {error}
        </p>
      )}
    </div>
  );
}
