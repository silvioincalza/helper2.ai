// Sets data-theme on <html> before the first paint: the saved choice, else the device setting.
// A classic script in <head> blocks paint, so there is no flash; it is a file, not inline, because the CSP is script-src 'self'.
// Keep the key in sync with THEME_KEY in src/lib/theme.ts.
(function () {
  var mq = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)');
  function saved() {
    try { return window.localStorage.getItem('h2.theme'); } catch { return null; }
  }
  function apply() {
    var s = saved();
    document.documentElement.dataset.theme = s === 'light' || s === 'dark' ? s : (mq && mq.matches ? 'dark' : 'light');
  }
  apply();
  // With no saved choice, follow the device when it switches (e.g. at sunset).
  if (mq && mq.addEventListener) mq.addEventListener('change', function () { if (!saved()) apply(); });
})();
