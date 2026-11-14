import { useEffect, useId, useRef, type ReactNode } from 'react';

export function AdminDialog({
  open,
  title,
  className,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  className?: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const opener = useRef<HTMLElement | null>(null);
  const titleId = useId();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.hasAttribute('open')) {
      opener.current =
        document.activeElement instanceof HTMLElement ? document.activeElement : null;
      if (typeof d.showModal === 'function') d.showModal();
      else d.setAttribute('open', '');
      d.querySelector<HTMLElement>('button')?.focus();
    }
    if (!open && d.hasAttribute('open')) {
      if (typeof d.close === 'function') d.close();
      else d.removeAttribute('open');
      opener.current?.focus();
    }
  }, [open]);
  return (
    <dialog
      ref={ref}
      className={['h2-admin__dialog', className].filter(Boolean).join(' ')}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onKeyDown={(e) => {
        e.stopPropagation();
        if (e.key === 'Escape') {
          e.preventDefault();
          onClose();
        }
      }}
    >
      <h3 id={titleId}>{title}</h3>
      {open && children}
    </dialog>
  );
}
