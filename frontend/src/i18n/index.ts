import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';

/**
 * The UI ships in English only. To add a locale: drop `<code>.json` next to en.json (same keys),
 * add it to LANGUAGES and RESOURCES. The language switcher appears automatically once there are two.
 */
export const LANGUAGES: readonly { code: string; label: string }[] = [{ code: 'en', label: 'English' }];
const RESOURCES = { en: { translation: en } };
export const LANG_KEY = 'h2.lang';

function initialLanguage(): string {
  try {
    const saved = window.localStorage.getItem(LANG_KEY);
    if (saved && LANGUAGES.some((l) => l.code === saved)) return saved;
  } catch {
    /* storage unavailable */
  }
  const nav = typeof navigator !== 'undefined' ? navigator.language : 'en';
  return LANGUAGES.find((l) => nav.toLowerCase().startsWith(l.code))?.code ?? 'en';
}

export function setLanguage(code: string): void {
  void i18n.changeLanguage(code);
  try {
    window.localStorage.setItem(LANG_KEY, code);
  } catch {
    /* storage unavailable: choice lasts for this session only */
  }
}

void i18n.use(initReactI18next).init({
  resources: RESOURCES,
  lng: initialLanguage(),
  fallbackLng: 'en',
  initAsync: false,
  interpolation: { escapeValue: false },
});

i18n.on('languageChanged', (lng) => {
  document.documentElement.lang = lng;
});
document.documentElement.lang = i18n.language;

export default i18n;
