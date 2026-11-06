import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';

/** The review queue's keys, in the order the help lists them. They work only while focus is in the queue. */
const REVIEW_KEYS = ['j', 'k', 'a', 'r', 'n', '?'] as const;

/**
 * The keys of the review queue in a modal dialog (WCAG 2.1.4: they act only while focus is inside the queue, and
 * never in a text field). Close or Escape closes it, and focus goes back to where it was.
 */
export function ReviewKeysHelp({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
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
      className="h2-admin__keys"
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onKeyDown={(e) => {
        // Keys pressed in the dialog never reach the queue behind it.
        e.stopPropagation();
        if (e.key === 'Escape') {
          e.preventDefault();
          onClose();
        }
      }}
    >
      <h3 id={titleId}>{t('admin.keys.title')}</h3>
      <dl>
        {REVIEW_KEYS.map((k) => (
          <div key={k}>
            <dt>
              <kbd>{k}</kbd>
            </dt>
            <dd>{t(`admin.keys.${k === '?' ? 'help' : k}`)}</dd>
          </div>
        ))}
      </dl>
      <p className="muted">{t('admin.keys.where')}</p>
      <button type="button" className="btn" onClick={onClose}>
        {t('admin.keys.close')}
      </button>
    </dialog>
  );
}
