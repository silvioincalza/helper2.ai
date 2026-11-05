import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import {
  askedQuestion,
  clampQuery,
  clampQuestion,
  hasOwnSearchBox,
  homeParams,
  MAX_QUESTION,
  parseSearchParams,
  patchState,
  RESULTS_PATH,
  type SearchState,
} from '@/lib/searchState';

/** Waits this long after the last keystroke before searching: live, but not one request per letter. */
const LIVE_SEARCH_MS = 700;
const MIN_LIVE_CHARS = 3;
/** From this many characters left, the box says how many remain. */
const SHOW_LEFT_FROM = 50;

/** True while the keyboard is in a field, where "/" is a character and not the shortcut to the box. */
function typingIn(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
}
/**
 * The one search box: the hero on /, above the results on /search, in the header on every other page. On /search
 * typing searches live; elsewhere Enter opens /search. Two buttons, for any length: Search (also Enter) and Ask the
 * guide (also Shift+Enter). The AI guide runs only when the user chooses it. Everything lives in the URL: `q` =
 * search, `ask` = the question the guide answered, plus the search filters. `hero`: the large box of the home page.
 */
export function OneBox({ hero = false }: { hero?: boolean }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [sp, setSp] = useSearchParams();
  // A page with its own box (/, /search) shows the search in the URL; in the header the box starts empty.
  const onHome = hasOwnSearchBox(pathname);
  const state = parseSearchParams(onHome ? sp : new URLSearchParams());
  const asked = onHome ? askedQuestion(sp) : '';
  // What the URL puts in the box: the question when the search is that question (cut to MAX_QUERY), else the search.
  const fromUrl = asked && clampQuery(asked) === state.q ? asked : state.q;
  const [text, setText] = useState(fromUrl);
  const [followed, setFollowed] = useState(fromUrl);
  if (fromUrl !== followed) {
    setFollowed(fromUrl);
    // The URL changed. If it only carries this box's own text (trimmed; a long question cut to MAX_QUERY), keep the
    // text as typed. Otherwise the change came from elsewhere (back button, example, radar card): follow it.
    if (clampQuery(text) !== state.q) setText(fromUrl);
  }

  const go = (next: SearchState, ask: string, current: URLSearchParams, replace = false) => {
    const params = homeParams(next, ask, current);
    // Results go to RESULTS_PATH; an empty box goes to the home page.
    const target = params.toString() ? RESULTS_PATH : '/';
    if (pathname === target) setSp(params, { replace, preventScrollReset: true });
    else void navigate({ pathname: target, search: params.toString() });
  };

  // Live search runs only from typing, and reads the URL as it is when the timer fires. Not an effect on
  // `text`: the router applies a navigation after the render that caused it, so an effect saw a stale URL
  // and, 700 ms after "Ask the guide", replaced the URL without `ask` (the guide answer vanished).
  const latest = useRef({ state, asked, pathname, sp });
  useEffect(() => {
    latest.current = { state, asked, pathname, sp };
  });
  const timer = useRef<number | undefined>(undefined);
  const cancelLive = () => window.clearTimeout(timer.current);
  useEffect(() => cancelLive, []);
  const onType = (typed: string) => {
    // Enter searches, so the box holds one line of text: a pasted line break becomes a space.
    const value = typed.replace(/[\r\n]+/g, ' ');
    setText(value);
    cancelLive();
    const q = clampQuery(value);
    if (q.length < MIN_LIVE_CHARS) return;
    timer.current = window.setTimeout(() => {
      const now = latest.current;
      // Only on the results page: on the home page a search moves to /search, so it waits for Enter.
      // New words drop the old question: the guide's picks answered that question, not these words. Shift+Enter
      // or "Ask the guide" asks again.
      if (now.pathname === RESULTS_PATH && q !== now.state.q)
        go(patchState(now.state, { q }), '', now.sp, true);
    }, LIVE_SEARCH_MS);
  };

  const runSearch = () => {
    cancelLive();
    const q = clampQuery(text);
    if (q) go(patchState(state, { q }), asked, sp);
  };
  /** Enter in an empty box: back to the empty home page. */
  const clear = () => {
    cancelLive();
    if (onHome && (state.q || asked)) go(parseSearchParams(new URLSearchParams()), '', sp);
  };
  /** With nothing to ask (an empty box, or under 3 characters) it only puts the cursor in the box. */
  const ask = () => {
    const q = clampQuestion(text);
    if (!q) return field.current?.focus();
    cancelLive();
    go(patchState(state, { q }), q, sp);
  };
  // Neither button is ever disabled (a disabled button cannot be found or explained by keyboard and screen-reader
  // users): with nothing to send it puts the cursor in the box.
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!text.trim()) field.current?.focus();
    else runSearch();
  };
  /** × and Esc: empty the box and keep the results on the page; the cursor stays in the box. */
  const emptyBox = () => {
    cancelLive();
    setText('');
    field.current?.focus();
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Escape' && text) {
      e.preventDefault();
      emptyBox();
      return;
    }
    if (e.key !== 'Enter' || e.nativeEvent.isComposing) return;
    // A textarea would add a line: Enter searches instead, as a one-line search field does, whatever the button
    // says. Asking the guide spends an AI answer, so the user chooses it: Shift+Enter or the button (Silvio, UJ-2).
    e.preventDefault();
    if (!text.trim()) clear();
    else if (e.shiftKey) ask();
    else runSearch();
  };

  // The box grows with the text. Measured at height auto with the transition off, then set back and moved to the
  // new height with the transition on, so it animates from one pixel height to the next (CSS caps it at six lines).
  const field = useRef<HTMLTextAreaElement>(null);
  const fit = useCallback(() => {
    const el = field.current;
    if (!el) return;
    const from = el.style.height;
    el.style.transition = 'none';
    el.style.height = 'auto';
    const content = el.scrollHeight;
    const to = `${content}px`;
    // Scrolling only past the CSS cap: no scrollbar flashes while the box grows towards a height it can reach.
    const cap = parseFloat(getComputedStyle(el).maxHeight);
    el.style.overflowY = Number.isFinite(cap) && content > cap ? 'auto' : 'hidden';
    el.style.height = from;
    void el.offsetHeight;
    el.style.transition = '';
    el.style.height = to;
  }, []);
  useLayoutEffect(fit, [text, fit]);
  useEffect(() => {
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, [fit]);

  // "/" from anywhere on the page puts the cursor in the box, as on most search sites.
  useEffect(() => {
    const onSlash = (e: globalThis.KeyboardEvent) => {
      if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey || typingIn(e.target)) return;
      e.preventDefault();
      field.current?.focus();
    };
    document.addEventListener('keydown', onSlash);
    return () => document.removeEventListener('keydown', onSlash);
  }, []);
  const left = MAX_QUESTION - text.length;

  return (
    <div className={`one-box${hero ? ' is-hero' : ''}`}>
      <form role="search" className="searchbox large" onSubmit={submit}>
        <span className="searchbox-icon" aria-hidden="true">
          <svg
            width="22"
            height="22"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          >
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20 16 16" />
          </svg>
        </span>
        <label htmlFor="q" className="sr-only">
          {t('search.label')}
        </label>
        <textarea
          ref={field}
          id="q"
          name="q"
          rows={1}
          className="searchbox-field"
          value={text}
          maxLength={MAX_QUESTION}
          autoComplete="off"
          spellCheck={false}
          placeholder={t('search.placeholder')}
          onChange={(e) => onType(e.target.value)}
          onKeyDown={onKey}
          aria-describedby={left <= SHOW_LEFT_FROM ? 'q-left' : undefined}
        />
        {left <= SHOW_LEFT_FROM && (
          <span
            id="q-left"
            className={`searchbox-left${left === 0 ? ' is-full' : ''}`}
            aria-live="polite"
          >
            <span aria-hidden="true">{t('search.charsLeftShort', { count: left })}</span>
            <span className="sr-only">{t('search.charsLeft', { count: left })}</span>
          </span>
        )}
        {text ? (
          <button
            type="button"
            className="searchbox-clear"
            aria-label={t('search.clear')}
            onClick={emptyBox}
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.2"
              strokeLinecap="round"
              aria-hidden="true"
            >
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        ) : (
          <kbd className="searchbox-slash" aria-hidden="true" title={t('search.slashHint')}>
            /
          </kbd>
        )}
        {/* Two buttons for any length of text. A new question replaces one still being answered. Each shows its key:
            ↵ on Search, ⇧↵ on Ask the guide (CSS hides key hints on touch devices and on a narrow box, where Ask the
            guide is an icon button). */}
        <button type="submit" className="btn btn-primary">
          {t('search.submit')}
          <kbd className="enter-key" aria-hidden="true">
            ↵
          </kbd>
        </button>
        <button type="button" className="btn one-box-ask" title={t('guide.ask')} onClick={ask}>
          <svg
            className="one-box-ask__icon"
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z" />
          </svg>
          <span className="one-box-ask__label">{t('guide.ask')}</span>
          <kbd className="enter-key" aria-hidden="true">
            ⇧↵
          </kbd>
        </button>
      </form>
    </div>
  );
}
