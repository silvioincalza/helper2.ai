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
  askedNow,
  askedQuestion,
  type AskedNow,
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
import { GuideAnswersLeft, useGuideAnswersLeft } from '@/features/guide/GuideAnswersLeft';

const LIVE_SEARCH_MS = 700;
const MIN_LIVE_CHARS = 3;
const SHOW_LEFT_FROM = 50;

function typingIn(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
}
export function OneBox({ hero = false }: { hero?: boolean }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [sp, setSp] = useSearchParams();
  const onHome = hasOwnSearchBox(pathname);
  const state = parseSearchParams(onHome ? sp : new URLSearchParams());
  const asked = onHome ? askedQuestion(sp) : '';
  const fromUrl = asked && clampQuery(asked) === state.q ? asked : state.q;
  const [text, setText] = useState(fromUrl);
  const [followed, setFollowed] = useState(fromUrl);
  const [lastSent, setLastSent] = useState<string | null>(null);
  if (fromUrl !== followed) {
    setFollowed(fromUrl);
    if (fromUrl === lastSent) setLastSent(null);
    else if (clampQuery(text) !== state.q) setText(fromUrl);
  }

  const go = (
    next: SearchState,
    ask: string,
    current: URLSearchParams,
    { replace = false, state }: { replace?: boolean; state?: AskedNow } = {},
  ) => {
    const params = homeParams(next, ask, current);
    const target = params.toString() ? RESULTS_PATH : '/';
    if (pathname === target) setSp(params, { replace, preventScrollReset: true, state });
    else void navigate({ pathname: target, search: params.toString() }, { state });
  };

  const latest = useRef({ state, asked, pathname, sp });
  useEffect(() => {
    latest.current = { state, asked, pathname, sp };
  });
  const timer = useRef<number | undefined>(undefined);
  const cancelLive = () => window.clearTimeout(timer.current);
  useEffect(() => cancelLive, []);
  const onType = (typed: string) => {
    const value = typed.replace(/[\r\n]+/g, ' ');
    setText(value);
    setTypeFirst(false);
    cancelLive();
    const q = clampQuery(value);
    if (q.length < MIN_LIVE_CHARS) return;
    timer.current = window.setTimeout(() => {
      const now = latest.current;
      if (now.pathname !== RESULTS_PATH || now.asked || q === now.state.q) return;
      setLastSent(q);
      go(patchState(now.state, { q }), '', now.sp, { replace: true });
    }, LIVE_SEARCH_MS);
  };

  const runSearch = () => {
    cancelLive();
    const q = clampQuery(text);
    if (q) go(patchState(state, { q }), clampQuery(asked) === q ? asked : '', sp);
  };
  const clear = () => {
    cancelLive();
    if (onHome && (state.q || asked)) go(parseSearchParams(new URLSearchParams()), '', sp);
  };
  const ask = () => {
    const q = clampQuestion(text);
    if (!q) return field.current?.focus();
    cancelLive();
    go(patchState(state, { q }), q, sp, { state: askedNow() });
  };
  const [typeFirst, setTypeFirst] = useState(false);
  const askToType = () => {
    if (typeFirst) field.current?.focus();
    else setTypeFirst(true);
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!text.trim()) askToType();
    else runSearch();
  };
  const hint = typeFirst && !text.trim();
  const answersLeft = useGuideAnswersLeft();
  const quota = text.trim() ? answersLeft : null;
  const emptyBox = () => {
    cancelLive();
    setText('');
    setTypeFirst(false);
    field.current?.focus();
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Escape' && text) {
      e.preventDefault();
      emptyBox();
      return;
    }
    if (e.key !== 'Enter' || e.nativeEvent.isComposing) return;
    e.preventDefault();
    if (!text.trim()) clear();
    else if (e.shiftKey) ask();
    else runSearch();
  };

  const field = useRef<HTMLTextAreaElement>(null);
  const fit = useCallback(() => {
    const el = field.current;
    if (!el) return;
    const from = el.style.height;
    el.style.transition = 'none';
    el.style.height = 'auto';
    const content = el.scrollHeight;
    const to = `${content}px`;
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

  useEffect(() => {
    const onSlash = (e: globalThis.KeyboardEvent) => {
      if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey || typingIn(e.target)) return;
      e.preventDefault();
      field.current?.focus();
    };
    document.addEventListener('keydown', onSlash);
    return () => document.removeEventListener('keydown', onSlash);
  }, []);
  useEffect(() => {
    if (typeFirst) field.current?.focus();
  }, [typeFirst]);
  const left = MAX_QUESTION - text.length;
  const describedBy =
    [left <= SHOW_LEFT_FROM && 'q-left', hint && 'q-hint', quota && 'q-quota']
      .filter(Boolean)
      .join(' ') || undefined;

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
          enterKeyHint="search"
          spellCheck={false}
          placeholder={t('search.placeholder')}
          onChange={(e) => onType(e.target.value)}
          onKeyDown={onKey}
          aria-describedby={describedBy}
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
        <button type="submit" className="btn btn-primary">
          {t('search.submit')}
          <kbd className="enter-key" aria-hidden="true">
            ↵
          </kbd>
        </button>
        <button
          type="button"
          id="q-ask"
          className="btn one-box-ask"
          aria-label={t('guide.ask')}
          title={t('guide.askTitle')}
          onClick={ask}
        >
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
          <span className="one-box-ask__label" aria-hidden="true">
            {t('guide.ask')}
          </span>
          <span className="one-box-ask__short" aria-hidden="true">
            {t('guide.askShort')}
          </span>
        </button>
      </form>
      {hint && (
        <p id="q-hint" className="one-box-note" role="status">
          {t('search.typeFirst')}
        </p>
      )}
      {quota && <GuideAnswersLeft id="q-quota" value={quota} />}
    </div>
  );
}
