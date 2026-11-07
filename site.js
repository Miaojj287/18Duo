/* Repository links are inferred for branch-based Pages deployments. */
(() => {
  'use strict';
  const frame = document.querySelector('#product-viewer-frame');
  if (frame) {
    let visible = false;
    const notifyLayout = () => frame.contentWindow?.postMessage({
      type: '18duo-viewer-layout', height: Math.floor(frame.getBoundingClientRect().height),
    }, location.origin);
    const notifyVisibility = () => frame.contentWindow?.postMessage({ type: '18duo-viewer-visibility', visible }, location.origin);
    new IntersectionObserver(entries => {
      visible = entries[0].isIntersecting;
      notifyVisibility();
    }, { threshold: .01 }).observe(frame);
    new ResizeObserver(notifyLayout).observe(frame);
    frame.addEventListener('load', () => { notifyLayout(); notifyVisibility(); });
  }
  const inferred = location.hostname.endsWith('.github.io') && location.pathname.split('/')[1]
    ? `${location.hostname.split('.')[0]}/${location.pathname.split('/')[1]}` : '';
  const repo = window.DUO_SITE?.repository || inferred;
  if (/^[\w.-]+\/[\w.-]+$/.test(repo)) {
    document.querySelectorAll('[data-repository]').forEach(link => {
      link.href = `https://github.com/${repo}`;
      link.hidden = false;
    });
  }
})();
