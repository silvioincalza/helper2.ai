import { Fragment } from 'react';
import { OriginLabel } from '@/ui/Badges';
import { aiMark } from '@/lib/aiMark';
import type { DisputedValue } from './fieldState';

export function DisputedValues({
  values,
  word,
}: {
  values: DisputedValue[];
  word: (value: DisputedValue) => string;
}) {
  return values.map((value, index) => (
    <Fragment key={`${index}-${value.value}`}>
      {index > 0 && ' · '}
      {value.aiOrigin ? (
        <>
          <span {...aiMark()}>{word(value)}</span> <OriginLabel origin={value.aiOrigin} />
        </>
      ) : (
        word(value)
      )}
    </Fragment>
  ));
}
