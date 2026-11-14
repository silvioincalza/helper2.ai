import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return (
    target.isContentEditable ||
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement
  );
}

export function stepRow(
  links: readonly HTMLElement[],
  current: Element | null,
  dir: 1 | -1,
): HTMLElement | undefined {
  if (links.length === 0) return undefined;
  const row = current?.closest('.h2-row');
  const at = row ? links.findIndex((l) => row.contains(l)) : -1;
  if (at === -1) return dir === 1 ? links[0] : links[links.length - 1];
  return links[Math.min(links.length - 1, Math.max(0, at + dir))];
}

export function ResultKeys({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const root = useRef<HTMLDivElement>(null);
  const help = useRef<HTMLDetailsElement>(null);
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey || isTyping(e.target)) return;
    const links = [...(root.current?.querySelectorAll<HTMLElement>('.h2-row__title') ?? [])];
    const act = (): boolean => {
      switch (e.key) {
        case 'j':
        case 'k': {
          const to = stepRow(links, document.activeElement, e.key === 'j' ? 1 : -1);
          to?.focus();
          return to !== undefined;
        }
        case '/': {
          const box = document.getElementById('q');
          if (!(box instanceof HTMLTextAreaElement || box instanceof HTMLInputElement))
            return false;
          box.focus();
          box.select();
          return true;
        }
        case 'g': {
          const askButton = document.getElementById('q-ask');
          askButton?.focus();
          return askButton !== null;
        }
        case '?': {
          if (!help.current) return false;
          help.current.open = !help.current.open;
          return true;
        }
        default:
          return false;
      }
    };
    if (act()) e.preventDefault();
  };
  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div ref={root} className="h2-results__keys" onKeyDown={onKeyDown}>
      {children}
      <details ref={help} className="h2-keys">
        <summary className="h2-fold-toggle">{t('results.keys.title')}</summary>
        <ul className="plain-list h2-keys__list">
          {(['move', 'open', 'box', 'ask', 'help'] as const).map((k) => (
            <li key={k}>
              <kbd>{t(`results.keys.${k}.key`)}</kbd> {t(`results.keys.${k}.does`)}
            </li>
          ))}
        </ul>
        <p className="h2-keys__note">{t('results.keys.note')}</p>
      </details>
    </div>
  );
}
