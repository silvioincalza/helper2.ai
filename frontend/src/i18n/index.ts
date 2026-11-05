import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';

/**
 * The UI ships in English only; the copy stays in en.json so a second language needs no code search.
 * A second language comes with its own change: a language choice, and its storage key on the Privacy page.
 */
void i18n.use(initReactI18next).init({
  resources: { en: { translation: en } },
  lng: 'en',
  fallbackLng: 'en',
  initAsync: false,
  interpolation: { escapeValue: false },
});

document.documentElement.lang = i18n.language;

export default i18n;
