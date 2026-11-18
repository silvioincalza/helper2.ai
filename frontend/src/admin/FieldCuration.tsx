import './adminText';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { problemDetail } from '@/api/client';
import type { ResourceField, ValueOrigin } from '@/api/types';
import { isAiOrigin } from '@/lib/aiMark';

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
    'AWESOME_LIST',
  ],
  pricing: ['FREE', 'FREEMIUM', 'PAID', 'OPEN_SOURCE_SELF_HOSTED'],
  openSource: ['true', 'false'],
  techLevel: ['BEGINNER', 'INTERMEDIATE', 'ADVANCED'],
  audience: ['USE_AI', 'BUILD_WITH_AI', 'BOTH'],
  language: [
    'en',
    'simple',
    'ar',
    'ca',
    'da',
    'de',
    'el',
    'es',
    'eu',
    'fi',
    'fr',
    'ga',
    'hi',
    'hu',
    'hy',
    'id',
    'it',
    'lt',
    'nl',
    'no',
    'pt',
    'ro',
    'ru',
    'sr',
    'sv',
    'ta',
    'tr',
  ],
};

const SETTABLE: readonly string[] = [
  'type',
  'pricing',
  'license',
  'openSource',
  'techLevel',
  'audience',
  'language',
  'publishedAt',
  'description',
];
const AI_FIELDS: readonly string[] = [
  'type',
  'pricing',
  'license',
  'openSource',
  'techLevel',
  'audience',
];
const DROPPABLE: readonly string[] = [...AI_FIELDS, 'useCase', 'platforms'];
const DESCRIPTION_MAX = 4000;

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
  return problemDetail(e, { status: [400] }) ?? t('admin.fieldCuration.failed');
}

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
  origin: ValueOrigin;
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
            ) : field === 'description' ? (
              <textarea
                value={chosen}
                required
                rows={5}
                maxLength={DESCRIPTION_MAX}
                onChange={(e) => setChosen(e.target.value)}
              />
            ) : (
              <input
                type={field === 'publishedAt' ? 'date' : 'text'}
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
