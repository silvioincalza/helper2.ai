import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { TermCommand } from '@/components/home/TermList';

/**
 * A back-office part's heading in the terminal style (ADR 0042): the title, then `$ helper2 review --open · 4`, with
 * the count from the API (none while it loads, or when the part has no count). The heading takes focus after Try
 * again ([headingRef], see useRetry).
 */
export function AdminHead({
  id,
  title,
  command,
  total = null,
  headingRef,
}: {
  id: string;
  title: string;
  command: string;
  total?: number | null;
  headingRef?: Ref<HTMLHeadingElement>;
}) {
  return (
    <div className="v4-term__bar">
      <h2 id={id} ref={headingRef} tabIndex={-1}>
        {title}
      </h2>
      <TermCommand command={command} total={total} />
    </div>
  );
}

/** `$ helper2 review · Loading…`: the part's command, then the loading word, announced politely. */
export function AdminLoading({ command }: { command: string }) {
  const { t } = useTranslation();
  return (
    <p role="status" className="v4-term__state">
      <span aria-hidden="true">$ {command} · </span>
      <span>{t('common.loading')}</span>
    </p>
  );
}

/** `$ helper2 review · 0 · Nothing in this queue.`: an empty part, in the same line. */
export function AdminEmpty({ command, text }: { command: string; text: string }) {
  return (
    <p className="v4-term__state">
      <span aria-hidden="true">$ {command} · 0 · </span>
      <span>{text}</span>
    </p>
  );
}
