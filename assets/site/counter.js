/* Downloads always follow the original link; statistics run independently. */
(() => {
  'use strict';

  const config = window.DUO_SITE?.counter;
  const stats = [...document.querySelectorAll('[data-download-stat]')];
  const numbers = [...document.querySelectorAll('[data-download-count]')];
  if (!config || (!stats.length && !numbers.length)) return;

  const local = location.protocol === 'file:' ||
    ['localhost', '127.0.0.1', '::1', '[::1]'].includes(location.hostname);
  if (local && !config.allowLocalhost) {
    stats.forEach(element => { element.textContent = '本地预览 · 下载统计未启用'; });
    numbers.forEach(element => { element.textContent = '—'; });
    return;
  }

  const format = new Intl.NumberFormat('zh-CN');
  let displayed = null;
  let inFlight = false;
  let lastAttempt = 0;
  let memoryMarker = null;
  const hours = Number.isFinite(config.dedupeHours) ? Math.max(1, config.dedupeHours) : 24;
  const interval = hours * 60 * 60 * 1000;
  const pendingTTL = 5 * 60 * 1000;
  const storageKey = `18duo:download:${config.namespace}:${config.key}`;
  const description = `下载按钮点击统计；同一浏览器 ${hours} 小时内计一次。点击不等于下载完成或安装；清除浏览器数据、换浏览器会重新计数。`;

  function show(value, label = '次下载点击') {
    if (!Number.isSafeInteger(value) || value < 0) throw new Error('Invalid count');
    // A page-load read can finish after a click response. Never replace a newer count.
    displayed = displayed === null ? value : Math.max(displayed, value);
    numbers.forEach(element => { element.textContent = format.format(displayed); });
    stats.forEach(element => { element.textContent = `${format.format(displayed)} ${label}`; });
    [...stats, ...numbers].forEach(element => {
      element.title = description;
      element.dataset.counterState = 'ready';
    });
  }

  function unavailable() {
    if (displayed !== null) return;
    stats.forEach(element => { element.textContent = '下载统计暂不可用'; });
    numbers.forEach(element => { element.textContent = '—'; });
    [...stats, ...numbers].forEach(element => { element.dataset.counterState = 'unavailable'; });
  }

  function readMarker() {
    try { return JSON.parse(localStorage.getItem(storageKey)) || memoryMarker; }
    catch { return memoryMarker; }
  }

  function writeMarker(value) {
    memoryMarker = value;
    try {
      if (value === null) localStorage.removeItem(storageKey);
      else localStorage.setItem(storageKey, JSON.stringify(value));
    } catch { /* Private browsing still has in-memory duplicate protection. */ }
  }

  async function request(path, increment = false) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(path, {
        method: 'GET',
        credentials: 'omit',
        referrerPolicy: 'no-referrer',
        cache: 'no-store',
        keepalive: increment,
        signal: controller.signal,
      });
      if (!response.ok) {
        const error = new Error(`Counter HTTP ${response.status}`);
        error.rejected = response.status >= 400 && response.status < 500;
        throw error;
      }
      const data = await response.json();
      if (data.value === null || data.value === '' || data.value === undefined) {
        throw new Error('Missing counter value');
      }
      const value = Number(data.value);
      if (!Number.isSafeInteger(value) || value < 0) throw new Error('Invalid counter response');
      return value;
    } finally { clearTimeout(timer); }
  }

  if (config.provider === 'github-release') {
    const inferred = location.hostname.endsWith('.github.io') && location.pathname.split('/')[1]
      ? `${location.hostname.split('.')[0]}/${location.pathname.split('/')[1]}` : '';
    const repository = window.DUO_SITE.repository || inferred;
    if (!/^[\w.-]+\/[\w.-]+$/.test(repository)) { unavailable(); return; }
    const url = `https://api.github.com/repos/${repository}/releases/latest`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 8000);
    fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal })
      .then(response => {
        if (!response.ok) throw new Error(`GitHub HTTP ${response.status}`);
        return response.json();
      })
      .then(release => {
        const assets = (release.assets || []).filter(asset => /\.apk$/i.test(asset.name));
        if (!assets.length) throw new Error('No release APK');
        show(assets.reduce((sum, asset) => sum + asset.download_count, 0), '次 GitHub 安装包下载');
        [...stats, ...numbers].forEach(element => {
          element.title = 'GitHub 最新 Release 中 APK 文件的下载次数；不等于独立人数或安装次数。';
        });
      }).catch(unavailable).finally(() => clearTimeout(timer));
    return;
  }

  if (config.provider !== 'abacus' ||
      !/^[A-Za-z0-9_.-]{3,64}$/.test(config.namespace) ||
      !/^[A-Za-z0-9_.-]{3,64}$/.test(config.key)) { unavailable(); return; }
  let base;
  try {
    base = new URL(config.baseURL);
    if (base.protocol !== 'https:') throw new Error('HTTPS required');
  } catch { unavailable(); return; }
  const endpoint = action => new URL(`/${action}/${config.namespace}/${config.key}`, base).href;
  request(endpoint('get')).then(show).catch(unavailable);

  async function increment() {
    const now = Date.now();
    const marker = readMarker();
    const age = marker && now - marker.at;
    if (inFlight || (marker && Number.isFinite(age) && age >= 0 &&
      age < (marker.pending ? pendingTTL : interval))) return;
    if (now - lastAttempt < 1500) return;
    inFlight = true;
    lastAttempt = now;
    // Reserve before the request so rapid clicks and other tabs share one attempt.
    writeMarker({ at: now, pending: true });
    try {
      const value = await request(endpoint('hit'), true);
      writeMarker({ at: now, pending: false });
      show(value);
    } catch (error) {
      // A network timeout is ambiguous: keep the short reservation to avoid
      // immediately duplicating a request which may already have reached the API.
      if (error.rejected) writeMarker(null);
      unavailable();
    } finally { inFlight = false; }
  }

  function onDownload(event) {
    if (event.defaultPrevented || !event.isTrusted) return;
    if (event.type === 'click' && event.button !== 0) return;
    if (event.type === 'auxclick' && event.button !== 1) return;
    const link = event.target instanceof Element ? event.target.closest('a[data-download]') : null;
    if (!link || !link.href) return;
    // Web Locks serialize clicks from multiple tabs when supported.
    if (navigator.locks) {
      navigator.locks.request(storageKey, increment).catch(() => {});
    } else { void increment(); }
  }
  document.addEventListener('click', onDownload);
  document.addEventListener('auxclick', onDownload);
})();
