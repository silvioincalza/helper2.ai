(function () {
  var saved = null;
  try {
    saved = window.localStorage.getItem('h2.theme');
  } catch {
    saved = null;
  }
  document.documentElement.dataset.theme = saved === 'dark' ? 'dark' : 'light';
})();
