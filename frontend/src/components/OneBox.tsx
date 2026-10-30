import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { askedQuestion, clampQuery, clampQuestion, homeParams, MAX_QUESTION, parseSearchParams, patchState, type SearchState } from '@/lib/searchState';

/** Waits this long after the last keystroke before searching: live, but not one request per letter. */
const LIVE_SEARCH_MS = 700;
const MIN_LIVE_CHARS = 3;
/** A need of this many words or more is a question for the guide; fewer words are a search. */
export const AUTO_ASK_WORDS = 4;

/** What the main button does for this text. Shift+Enter always asks the guide. */
export function actionFor(text: string): 'search' | 'ask' {
  return text.trim().split(/\s+/).filter(Boolean).length >= AUTO_ASK_WORDS ? 'ask' : 'search';
}

/**
 * The one entry point, in the header of every page. Typing searches every source live on the home page;
 * "Ask the guide" (or Shift+Enter) also asks the AI guide. On any other page it takes you home with the query.
 * Everything lives in the URL: `q` = search, `ask` = the question the guide answered, plus the search filters.
 * `hero`: the large box at the top of the home page; elsewhere it sits in the header.
 */
export function OneBox({ hero = false }: { hero?: boolean }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [sp, setSp] = useSearchParams();
  const onHome = pathname === '/';
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
    if (onHome) setSp(params, { replace, preventScrollReset: true });
    else void navigate({ pathname: '/', search: params.toString() });
  };

  // Live search runs only from typing, and reads the URL as it is when the timer fires. Not an effect on
  // `text`: the router applies a navigation after the render that caused it, so an effect saw a stale URL
  // and, 700 ms after "Ask the guide", replaced the URL without `ask` (the guide answer vanished).
  const latest = useRef({ state, asked, onHome, sp });
  useEffect(() => { latest.current = { state, asked, onHome, sp }; });
  const timer = useRef<number | undefined>(undefined);
  const cancelLive = () => window.clearTimeout(timer.current);
  useEffect(() => cancelLive, []);
  const onType = (value: string) => {
    setText(value);
    cancelLive();
    const q = clampQuery(value);
    if (q.length < MIN_LIVE_CHARS) return;
    timer.current = window.setTimeout(() => {
      const now = latest.current;
      if (now.onHome && q !== now.state.q) go(patchState(now.state, { q }), now.asked, now.sp, true);
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
  const ask = () => {
    const q = clampQuestion(text);
    if (!q) return;
    cancelLive();
    go(patchState(state, { q }), q, sp);
  };
  const action = actionFor(text);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (action === 'ask') ask();
    else runSearch();
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Enter') return;
    if (e.shiftKey) {
      e.preventDefault();
      ask();
    } else if (!text.trim()) {
      // An empty box disables the submit button, so the form never submits: Enter is handled here.
      e.preventDefault();
      clear();
    }
  };
  const asking = action === 'ask';

  return (
    <div className={`one-box${hero ? ' is-hero' : ''}`}>
      <form role="search" className="searchbox large" onSubmit={submit}>
        <span className="searchbox-icon" aria-hidden="true">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <circle cx="11" cy="11" r="7" /><path d="M20 20 16 16" />
          </svg>
        </span>
        <label htmlFor="q" className="sr-only">{t('search.label')}</label>
        <input id="q" name="q" type="search" value={text} maxLength={MAX_QUESTION} autoComplete="off"
          placeholder={t(hero ? 'search.heroPlaceholder' : 'search.placeholder')} onChange={(e) => onType(e.target.value)} onKeyDown={onKey} />
        {/* One button whose job follows the text: a few words search, a sentence asks. A new question replaces one
            still being answered. */}
        <button type="submit" className="btn btn-primary" disabled={asking ? !clampQuestion(text) : !text.trim()}>
          {asking ? t('guide.ask') : t('search.submit')}
          <kbd className="enter-key" aria-hidden="true">↵</kbd>
        </button>
      </form>
    </div>
  );
}
