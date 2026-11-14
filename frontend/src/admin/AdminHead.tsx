import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { TermCommand } from '@/features/home/TermList';

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
    <div className="h2-term__bar">
      <h2 id={id} ref={headingRef} tabIndex={-1}>
        {title}
      </h2>
      <TermCommand command={command} total={total} />
    </div>
  );
}

export function AdminLoading({ command }: { command: string }) {
  const { t } = useTranslation();
  return (
    <p role="status" className="h2-term__state">
      <span aria-hidden="true">$ {command} · </span>
      <span>{t('common.loading')}</span>
    </p>
  );
}

export function AdminEmpty({ command, text }: { command: string; text: string }) {
  return (
    <p className="h2-term__state">
      <span aria-hidden="true">$ {command} · 0 · </span>
      <span>{text}</span>
    </p>
  );
}
