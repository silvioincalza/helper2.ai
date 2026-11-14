import { useTranslation } from 'react-i18next';
import type { ResourceDetail } from '@/api/types';
import { OriginLabel } from '@/ui/Badges';
import { Icon } from '@/ui/Icon';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { FIELDS, fieldWord, stateOf } from './fieldState';

export function FieldStates({ detail }: { detail: ResourceDetail }) {
  const { t } = useTranslation();
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
                    {state.values.map((v) => fieldWord(t, field, v.value)).join(' · ')}
                  </dd>
                ) : (
                  <dd>
                    <span {...(isAiOrigin(state.origin) ? aiMark() : {})}>
                      {state.values.map((v) => fieldWord(t, field, v)).join(', ')}
                    </span>{' '}
                    <OriginLabel origin={state.origin} />
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
