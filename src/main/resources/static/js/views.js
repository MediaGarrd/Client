export function initViews(onShow) {
  const views = document.querySelectorAll('[data-view]');
  const links = document.querySelectorAll('[data-view-link]');

  function show() {
    const current = location.hash === '#config' ? 'config' : 'dashboard';
    views.forEach((view) => { view.hidden = view.dataset.view !== current; });
    links.forEach((link) => link.setAttribute('aria-pressed', String(link.dataset.viewLink === current)));
    onShow(current);
  }

  links.forEach((link) => link.addEventListener('click', () => {
    location.hash = link.dataset.viewLink === 'config' ? 'config' : '';
  }));
  window.addEventListener('hashchange', show);
  show();
}
