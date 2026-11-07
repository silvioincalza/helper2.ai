import i18n from '@/i18n';
import admin from '@/i18n/locales/en.admin.json';

/**
 * The back office's copy lives in its own file and joins the translations when an admin module loads,
 * so the public JavaScript carries none of it (ADR 0026).
 */
i18n.addResourceBundle('en', 'translation', admin, true, false);
