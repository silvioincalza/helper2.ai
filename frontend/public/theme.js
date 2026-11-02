// Sets data-theme on <html> before the first paint: dark when the visitor chose it, else light (the default).
// A classic script in <head> blocks paint, so there is no flash; it is a file, not inline, because the CSP is script-src 'self'.
// Keep the key in sync with THEME_KEY in src/lib/theme.ts.
(function () {
  var saved = null;
  try { saved = window.localStorage.getItem('h2.theme'); } catch { saved = null; }
  document.documentElement.dataset.theme = saved === 'dark' ? 'dark' : 'light';
})();
