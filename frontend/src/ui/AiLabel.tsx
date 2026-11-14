import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

export type AiKind = 'written' | 'read' | 'estimated';

const EDGE = 8;
const GAP = 4;
const PADDING = 18;
const HEIGHT = 22;

export interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

interface Spot {
  left: number;
  top: number;
}

export function bubbleAt(
  mark: Box,
  bubble: { width: number; height: number },
  screen: { width: number; height: number },
): Spot {
  const centre = (mark.left + mark.right) / 2;
  const widest = Math.max(EDGE, screen.width - bubble.width - EDGE);
  const left = Math.min(widest, Math.max(EDGE, centre - bubble.width / 2));
  const above = mark.top - GAP - bubble.height;
  const top = above >= EDGE ? above : mark.bottom + GAP;
  return { left, top };
}

function spotFor(mark: HTMLElement): Spot {
  const words = mark.querySelector('.ai-label__words');
  return bubbleAt(
    mark.getBoundingClientRect(),
    { width: (words?.scrollWidth ?? 0) + PADDING, height: HEIGHT },
    { width: window.innerWidth, height: window.innerHeight },
  );
}

function useBubble() {
  const [spot, setSpot] = useState<Spot | null>(null);
  useEffect(() => {
    if (!spot) return undefined;
    const hide = () => setSpot(null);
    window.addEventListener('scroll', hide, { passive: true });
    return () => window.removeEventListener('scroll', hide);
  }, [spot]);
  const show = useCallback((mark: HTMLElement) => setSpot(spotFor(mark)), []);
  const hide = useCallback(() => setSpot(null), []);
  return { spot, show, hide };
}

type Bubble = ReturnType<typeof useBubble>;

function useControlFocus(mark: HTMLElement | null, bubble: Bubble) {
  const { show, hide } = bubble;
  useEffect(() => {
    const control = mark?.closest('button, a');
    if (!mark || !control) return undefined;
    const onFocus = () => {
      if (!window.matchMedia?.('(hover: none)').matches) show(mark);
    };
    control.addEventListener('focus', onFocus);
    control.addEventListener('blur', hide);
    return () => {
      control.removeEventListener('focus', onFocus);
      control.removeEventListener('blur', hide);
    };
  }, [mark, show, hide]);
}

function MarkInControl({
  className,
  kind,
  bubble,
  children,
}: {
  className: string;
  kind: AiKind;
  bubble: Bubble;
  children: ReactNode;
}) {
  const [mark, setMark] = useState<HTMLSpanElement | null>(null);
  useControlFocus(mark, bubble);
  return (
    <span
      ref={setMark}
      className={className}
      data-ai-kind={kind}
      onMouseEnter={(e) => bubble.show(e.currentTarget)}
      onMouseLeave={bubble.hide}
    >
      {children}
    </span>
  );
}

export function AiLabel({
  children,
  className,
  kind = 'written',
  inControl = false,
}: {
  children?: ReactNode;
  className?: string;
  kind?: AiKind;
  inControl?: boolean;
}) {
  const { t } = useTranslation();
  const [focused, setFocused] = useState(false);
  const bubble = useBubble();
  const classes = ['ai-label', `ai-label--${kind}`, bubble.spot && 'is-open', className].filter(
    Boolean,
  );
  const dot = <span className={`ai-dot ai-dot--${kind}`} aria-hidden="true" />;
  const words = (
    <span className="ai-label__words" style={bubble.spot ?? undefined}>
      {children ?? t('picks.aiWritten')}
    </span>
  );
  if (inControl)
    return (
      <MarkInControl
        className={[...classes, 'ai-label--in-control'].join(' ')}
        kind={kind}
        bubble={bubble}
      >
        {dot}
        {words}
      </MarkInControl>
    );
  return (
    <button
      type="button"
      className={classes.join(' ')}
      data-ai-kind={kind}
      onMouseEnter={(e) => bubble.show(e.currentTarget)}
      onMouseLeave={() => {
        if (!focused) bubble.hide();
      }}
      onFocus={(e) => {
        setFocused(true);
        bubble.show(e.currentTarget);
      }}
      onClick={(e) => bubble.show(e.currentTarget)}
      onBlur={() => {
        setFocused(false);
        bubble.hide();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') bubble.hide();
      }}
    >
      {dot}
      {words}
    </button>
  );
}

export function AiKey() {
  const { t } = useTranslation();
  return (
    <span className="ai-key">
      {t('aiKey.title')}{' '}
      {(['written', 'read', 'estimated'] as const).map((kind) => (
        <span key={kind} className="ai-key__item">
          <span className={`ai-dot ai-dot--${kind}`} aria-hidden="true" /> {t(`aiKey.${kind}`)}
        </span>
      ))}
    </span>
  );
}
