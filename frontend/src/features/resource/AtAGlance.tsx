import type { ElementType, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResourceDetail } from '@/api/types';
import { OriginLabel } from '@/ui/Badges';
import { Icon } from '@/ui/Icon';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { fieldWord, stateOf, type Field, type FieldState } from './fieldState';

const USE: Field[] = ['pricing', 'platforms', 'useCase', 'audience'];
const BUILD: Field[] = ['license', 'openSource', 'techLevel'];

type Known = Exclude<FieldState, { kind: 'notStated' }>;

export function AtAGlance({
  detail,
  groupTag: GroupTag,
}: {
  detail: ResourceDetail;
  groupTag: ElementType;
}) {
  const { t } = useTranslation();
  const states = new Map([...USE, ...BUILD].map((field) => [field, stateOf(field, detail)]));
  const known = (fields: Field[]) =>
    fields.flatMap((field) => {
      const state = states.get(field)!;
      return state.kind === 'notStated' ? [] : [{ field, state }];
    });
  const missing = [...USE, ...BUILD].filter((field) => states.get(field)!.kind === 'notStated');

  const shown = (field: Field, state: Known) => {
    if (state.kind === 'conflict')
      return (
        <dd className="is-conflict">
          <Icon name="warn" /> {t('date.conflicting')}:{' '}
          {state.values
            .map((v) => {
              const word = fieldWord(t, field, v.value);
              return v.sourceId ? `${word} (${v.sourceId})` : word;
            })
            .join(' · ')}
        </dd>
      );
    const words = state.values.map((v) => fieldWord(t, field, v)).join(', ');
    if (state.origin === 'SOURCE_PROVIDED') return <dd>{words}</dd>;
    const ai = isAiOrigin(state.origin);
    return (
      <dd>
        <span {...(ai ? aiMark() : {})}>{words}</span> <OriginLabel origin={state.origin} />
      </dd>
    );
  };

  const group = (title: string, fields: Field[], extra?: ReactNode) => {
    const rows = known(fields);
    if (rows.length === 0 && !extra) return null;
    return (
      <div className="h2-facts__group">
        <GroupTag>{title}</GroupTag>
        <dl>
          {rows.map(({ field, state }) => (
            <div key={field}>
              <dt>{t(`detail.field.${field}`)}</dt>
              {shown(field, state)}
            </div>
          ))}
          {extra}
        </dl>
      </div>
    );
  };

  const tools = detail.card.compatibleWith;
  const worksWith = tools.length > 0 && (
    <div key="worksWith">
      <dt>{t('card.compatibleWith')}</dt>
      <dd>
        <ul className="plain-list h2-facts__tools">
          {tools.map((f) => {
            const why = t(`compat.basis.${f.basis}`, {
              evidence: f.evidence,
              source: f.sourceId ?? '',
            });
            return (
              <li key={f.tool} data-tool={f.tool} title={why}>
                {f.label}
                <span className="sr-only"> ({why})</span>
              </li>
            );
          })}
        </ul>
      </dd>
    </div>
  );

  const use = group(t('results.use'), USE);
  const build = group(t('results.build'), BUILD, worksWith);
  return (
    <>
      {(use || build) && (
        <div className="h2-facts">
          {use}
          {build}
        </div>
      )}
      {missing.length > 0 && (
        <p className="h2-facts__missing">
          {t('resource.notStatedBy')}{' '}
          {missing.map((field) => t(`resource.notStatedName.${field}`)).join(', ')}
        </p>
      )}
    </>
  );
}
