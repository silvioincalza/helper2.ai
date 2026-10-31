import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Heading } from '@/components/ui/Heading';
import { ConnectPart } from './ConnectPart';
import { GuideMeButton, useGuideToggle } from './GuideMe';
import { StepsPart } from './StepsPart';

/**
 * "Guide me" on a tool (ADR 0031): how to install, configure, use and connect it, from its own sources only. Two
 * parts, loaded when opened: snippets per AI tool (ConnectPart), then checked steps and a follow-up question
 * (StepsPart). Focus moves to its heading when it opens.
 */
export function GuideBody({ id, title, level }: { id: string; title: string; level: number }) {
  const { t } = useTranslation();
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => heading.current?.focus(), []);
  return (
    <div className="panel setup-panel">
      <Heading level={level} ref={heading} tabIndex={-1} className="setup-heading">
        {t('setup.title', { title, interpolation: { escapeValue: false } })}
      </Heading>
      <ConnectPart id={id} level={level + 1} />
      <StepsPart id={id} title={title} level={level + 1} />
    </div>
  );
}

/** The detail page's Guide me: a primary button, the panel under it. */
export function SetupGuidePanel({ id, title }: { id: string; title: string }) {
  const { t } = useTranslation();
  const { open, toggle } = useGuideToggle();
  const bodyId = `setup-${useId()}`;
  return (
    <section className="setup" aria-label={t('setup.title', { title, interpolation: { escapeValue: false } })}>
      <GuideMeButton title={title} open={open} onToggle={toggle} bodyId={bodyId} primary />
      <div id={bodyId} hidden={!open}>{open && <GuideBody id={id} title={title} level={2} />}</div>
    </section>
  );
}
