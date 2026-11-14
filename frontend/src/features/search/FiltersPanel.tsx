import { useEffect, useId, useRef, type ReactNode, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';

export function FiltersPanel({
  id,
  open,
  drawer,
  total,
  loading,
  toggle,
  onClose,
  children,
}: {
  id: string;
  open: boolean;
  drawer: boolean;
  total: number;
  loading: boolean;
  toggle: RefObject<HTMLButtonElement | null>;
  onClose: () => void;
  children: ReactNode;
}) {
  if (!drawer)
    return (
      <div id={id} className="h2-filters" hidden={!open}>
        {children}
      </div>
    );
  return (
    <FiltersDrawer
      id={id}
      open={open}
      total={total}
      loading={loading}
      toggle={toggle}
      onClose={onClose}
    >
      {children}
    </FiltersDrawer>
  );
}

function FiltersDrawer({
  id,
  open,
  total,
  loading,
  toggle,
  onClose,
  children,
}: {
  id: string;
  open: boolean;
  total: number;
  loading: boolean;
  toggle: RefObject<HTMLButtonElement | null>;
  onClose: () => void;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) {
      if (typeof d.showModal === 'function') d.showModal();
      else d.setAttribute('open', '');
      d.querySelector<HTMLElement>('.h2-drawer__close')?.focus();
    }
    if (!open && d.open) {
      if (typeof d.close === 'function') d.close();
      else d.removeAttribute('open');
    }
  }, [open]);
  const close = () => {
    onClose();
    toggle.current?.focus();
  };
  return (
    <dialog
      ref={ref}
      id={id}
      className="h2-drawer"
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        close();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          close();
        }
      }}
    >
      <div className="h2-drawer__head">
        <h2 id={titleId} className="h2-drawer__title">
          {t('filters.title')}
        </h2>
        <button type="button" className="btn h2-drawer__close" onClick={close}>
          {t('filters.close')}
        </button>
      </div>
      <div className="h2-drawer__body">{children}</div>
      <div className="h2-drawer__foot">
        <button type="button" className="btn btn-primary" aria-busy={loading} onClick={close}>
          {loading ? t('filters.searching') : t('filters.showResults', { count: total })}
        </button>
      </div>
    </dialog>
  );
}
