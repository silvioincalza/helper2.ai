import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/** A key typed into a field is text, not a shortcut. */
function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return (
    target.isContentEditable ||
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement
  );
}

/**
 * The row title to focus after [current] in direction [dir]: the next or previous one, the first when the focus is
 * not on a row yet (j) or the last (k), and the same one at either end of the list.
 */
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

/**
 * Keys for the results (v5): j and k move between rows, Enter opens one (the link does that), / goes to the box, g
 * asks the guide the same words, ? opens the list of keys. They work only while the focus is inside the results, so
 * a one-letter key never fires by accident elsewhere (WCAG 2.1.4), and never while typing or with Ctrl, Alt or ⌘.
 * The list of keys is shown only to a mouse and keyboard (CSS: pointer fine).
 */
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
          const ask = document.querySelector<HTMLButtonElement>('.one-box-ask');
          ask?.click();
          return ask !== null;
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
    // The keys live on the results' own subtree; every control inside keeps its own keyboard use.
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
