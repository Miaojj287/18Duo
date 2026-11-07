/* 18Duo adapter. The supplied scene mixer, hinge simulator and controls remain in the viewer runtime. */
(() => {
  'use strict';
  const labels = ['PT_SliderLanding', 'PT_Landscape', 'PT_Portrait', 'PT_Closed', 'PT_Laptop', 'PT_Tent', 'PT_Durability'];
  const screenshotRoot = new URL('../screenshots/', document.baseURI);
  // Content-addressed screenshots keep older scene/HTTP caches from
  // restoring removed icons or the previous reminder text.
  const screenFiles = Object.freeze({
    inner: 'home-inner.493564f41d5e.webp',
    split: 'split-screen.9b5f74d08b1d.webp',
  });
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const stateNames = { PT_SliderLanding: '折叠设计', PT_Landscape: '内屏横向', PT_Portrait: '内屏竖向', PT_Closed: '外屏桌面', PT_Laptop: '半折桌面', PT_Tent: '倒 V 待机', PT_Durability: '书本模式' };
  const singleton = () => window.__duoRequire?.('ApY4q/z+mDxRKgUHvbiF')?.ProductViewerWebGLSingleton;
  const scene = () => singleton()?.scene;
  function activateProductScene() {
    const current = singleton()?.scene;
    if (!current?.created) return current;
    window.Lotus.Lotus.scene = current;
    current.loop.create(current);
    return current;
  }
  const redraw = () => activateProductScene()?.loop.tryRequestAnimationFrame();
  const waitFrame = () => new Promise(resolve => requestAnimationFrame(resolve));
  async function ready(timeout = 40000) {
    const start = performance.now();
    while (performance.now() - start < timeout) {
      const current = scene();
      const fade = current?.hinge?.component?.scripts.get('FadeThroughBlack');
      if (current?.created && fade?._meshesReady && fade.SCREEN_TEXTURES) return current;
      await waitFrame();
    }
    throw new Error('折叠屏展示加载超时');
  }
  function notify(state) {
    const title = stateNames[state] || '折叠设计';
    document.querySelector('.product-viewer-component').setAttribute('aria-label', `18Duo，${title}，屏幕显示真实应用截图`);
    if (window.parent !== window) window.parent.postMessage({ type: '18duo-viewer-state', state, title }, window.location.origin);
  }
  function select(state) {
    const current = activateProductScene();
    if (!current?.created) return;
    current.__duoFoldPlayback?.stop();
    if (state === 'PT_Book') state = 'PT_Durability';
    const index = labels.indexOf(state);
    if (index < 0) return;
    current.__duoIntendedState = state;
    const item = document.querySelector(`.control-item[data-viewer-state="${state}"]`);
    const button = item?.querySelector('.control-item-open');
    if (current.__duoSelectedState === state && button?.getAttribute('aria-expanded') === 'true') return;
    expandMobileState(state);
    current.onViewChange({ item: item || { dataset: { viewerState: state } }, index });
    redraw();
  }
  function fold(progress, pointer = true) {
    const current = activateProductScene();
    if (!current?.created) return;
    current.__duoFoldPlayback?.stop();
    if (current.__duoBook) select('PT_SliderLanding');
    current.__duoIntendedState = 'PT_SliderLanding';
    current.onSliderActive();
    current.onSliderUpdate({ gestureProgress: Math.max(0, Math.min(1, progress)), pointer, isClick: false });
    redraw();
  }
  function reset() {
    const current = activateProductScene();
    if (!current?.created) return;
    current.__duoFoldPlayback?.stop();
    const input = document.querySelector('.control-detent-slider--input');
    input.step = 'any';
    input.value = String(1 / 3);
    document.querySelector('.product-viewer-component').__duoCore?.resetDetentSlider();
    current.hinge.sliderProgress = 1 / 3;
    current.hinge.sliderPointer = false;
    current.__duoIntendedState = 'PT_SliderLanding';
    expandMobileState('PT_SliderLanding');
    // Returning from any pose uses the authored camera weights and hinge
    // spring. The 9999-stiffness tracking mode belongs only to direct input.
    const item = document.querySelector('[data-viewer-state="PT_SliderLanding"]');
    current.onViewChange({ item, index: 0 });
  }
  function expandMobileState(state, animate = true) {
    const controls = document.querySelector('.controls');
    controls.removeAttribute('aria-hidden');
    document.querySelectorAll('.control-item[data-viewer-state]').forEach(item => {
      const selected = item.dataset.viewerState === state;
      const wasExpanded = item.classList.contains('expanded');
      item.classList.toggle('expanded', selected);
      const button = item.querySelector('.control-item-open');
      button.setAttribute('aria-expanded', String(selected));
      button.removeAttribute('aria-hidden');
      button.removeAttribute('tabindex');
      const content = item.querySelector('.control-item-content');
      content.setAttribute('aria-hidden', String(!selected));
      if (animate && selected && !wasExpanded) {
        content.getAnimations().forEach(animation => animation.cancel());
        content.animate([{ opacity: 0 }, { opacity: 1 }], {
          duration: reducedMotion.matches ? 160 : 180,
          easing: 'cubic-bezier(0.23, 1, 0.32, 1)',
        });
      }
    });
  }
  function installSliderSynchronization(current, host) {
    const core = host.__duoCore;
    if (!core) throw new Error('原滑条控件尚未就绪');
    current.__duoSliderCore = core;
    new ResizeObserver(() => { core.sliderNeedsBounds = true; core.onResizeDebounced(); }).observe(host.querySelector('.control-detent-slider--input-wrapper'));
    // A previous API reset or preset can leave the pointer-start value behind
    // the visible elastic thumb. Start the native drag from its live position.
    const thumb = host.querySelector('.control-detent-slider--thumb');
    let dragStart = null, suppressClickUntil = 0;
    window.addEventListener('pointermove', event => {
      if (dragStart && dragStart.id === event.pointerId && Math.abs(event.clientX - dragStart.x) > 4) dragStart.moved = true;
    }, true);
    window.addEventListener('pointerup', event => {
      if (dragStart?.id !== event.pointerId) return;
      if (dragStart.moved) suppressClickUntil = performance.now() + 150;
      dragStart = null;
    }, true);
    thumb.addEventListener('pointerdown', event => {
      dragStart = { id: event.pointerId, x: event.clientX, moved: false };
      current.__duoIntendedState = 'PT_SliderLanding';
      current.__duoSelectedState = 'PT_SliderLanding';
      const position = current.hinge.simulator.currentState.position;
      core.detentOutputThumbPosition = (core.isRTL ? 1 - position : position) * core.detentSliderWidth;
      core.detentsInputValue = position;
      core.gestureRatio = position;
    }, true);
    // Keep the reference track click, stretched thumb, hinge inertia and
    // magnet engagement handlers. Their shared simulator remains unchanged.
    host.querySelector('.control-detent-slider--output').addEventListener('click', event => {
      // A released elastic thumb can still trail the pointer. Ignore the
      // synthetic track click following that drag; preserve deliberate taps.
      if (performance.now() < suppressClickUntil) {
        event.preventDefault(); event.stopImmediatePropagation(); return;
      }
      current.__duoIntendedState = 'PT_SliderLanding';
      current.__duoSelectedState = 'PT_SliderLanding';
    }, true);
  }
  function installMobileControls(host) {
    const viewer = singleton();
    // The integrated controls select the native scene directly.
    viewer.onAAPUpdate = () => {};
    host.addEventListener('click', event => {
      const button = event.target.closest('.control-item-open');
      if (!button) return;
      scene().__duoIntendedState = button.closest('.control-item').dataset.viewerState;
      event.preventDefault();
      event.stopImmediatePropagation();
      select(button.closest('.control-item').dataset.viewerState);
    }, true);
    for (const name of ['pointerdown', 'click', 'input', 'keydown']) {
      host.addEventListener(name, activateProductScene, true);
    }
    for (const name of ['pointermove', 'pointerup']) {
      window.addEventListener(name, () => {
        if (scene()?.hinge?.sliderIsActive) activateProductScene();
      }, true);
    }
    const sync = () => {
      expandMobileState(scene()?.__duoSelectedState || 'PT_SliderLanding');
    };
    window.addEventListener('resize', sync);
    // The source carousel can finish a breakpoint animation after our
    // selected pose is committed. Keep the embedded controls on that pose.
    const controls = host.querySelector('.controls');
    new MutationObserver(() => {
      const selected = scene()?.__duoSelectedState;
      if (!selected) return;
      const mismatched = [...controls.querySelectorAll('.control-item[data-viewer-state]')].some(item => {
        const expected = item.dataset.viewerState === selected;
        return item.classList.contains('expanded') !== expected ||
          item.querySelector('.control-item-open').getAttribute('aria-expanded') !== String(expected);
      });
      if (mismatched) expandMobileState(selected, false);
    }).observe(controls, { subtree: true, attributes: true, attributeFilter: ['class', 'aria-expanded'] });
    sync();
  }
  function texture(loaded, clockwise = 0) {
    const T = window.Lotus.THREE;
    let result = loaded;
    if (clockwise) {
      const image = loaded.image;
      const canvas = document.createElement('canvas');
      canvas.width = image.height;
      canvas.height = image.width;
      const context = canvas.getContext('2d');
      context.translate(canvas.width / 2, canvas.height / 2);
      context.rotate(clockwise * Math.PI / 180);
      context.drawImage(image, -image.width / 2, -image.height / 2);
      result = new T.CanvasTexture(canvas);
    }
    result.flipY = false;
    result.offset.y = 1;
    result.repeat.y = -1;
    result.colorSpace = T.SRGBColorSpace;
    result.anisotropy = 8;
    result.needsUpdate = true;
    return result;
  }
  async function installScreens(current) {
    const fade = current.hinge.component.scripts.get('FadeThroughBlack');
    const loader = new window.Lotus.THREE.TextureLoader();
    const verifiedScreen = (id, filename) => {
      const url = new URL(filename, screenshotRoot).href;
      const loaded = fade.SCREEN_TEXTURES[id];
      return loaded?.image?.src === url ? Promise.resolve(loaded) : loader.loadAsync(url);
    };
    const currentScreens = await Promise.all([
      verifiedScreen('nQNNCnZOZSsSiUe', screenFiles.inner),
      verifiedScreen('GbTVLjkwBEDPeJt', screenFiles.split),
    ]);
    fade.SCREEN_TEXTURES.nQNNCnZOZSsSiUe = currentScreens[0];
    fade.SCREEN_TEXTURES.GbTVLjkwBEDPeJt = currentScreens[1];
    // Reuse textures already loaded by the native scene's asset list.
    const entries = [
      ['inner', 'nQNNCnZOZSsSiUe', 0], ['portrait', 'zDlNKMoTVfSQJgX', 90],
      ['cover', 'NqqQPGHpHcQDhJp', 0], ['split', 'GbTVLjkwBEDPeJt', 90],
      ['standby', 'NOffPBTJaXSVsiS', 270], ['book', 'aHbysYyIDlgYobQ', 0],
    ].map(([key, id, rotation]) => [`18duo-${key}`, texture(fade.SCREEN_TEXTURES[id], rotation)]);
    Object.assign(fade.SCREEN_TEXTURES, Object.fromEntries(entries));
    const T = window.Lotus.THREE;
    // A black screen texture participates in the native fade-through-black
    // transition. No extra image request or separate animation is necessary.
    const screenOff = new T.DataTexture(new Uint8Array([0, 0, 0, 255]), 1, 1, T.RGBAFormat);
    screenOff.colorSpace = T.SRGBColorSpace;
    screenOff.needsUpdate = true;
    fade.SCREEN_TEXTURES['18duo-off'] = screenOff;
    const inner = '18duo-inner', cover = '18duo-cover';
    for (const state of Object.keys(fade.TEXTURE_LOOKUP)) fade.TEXTURE_LOOKUP[state] = [inner, cover];
    fade.TEXTURE_LOOKUP.PT_Portrait = ['18duo-portrait', cover];
    fade.TEXTURE_LOOKUP.PT_Laptop = ['18duo-split', cover];
    fade.TEXTURE_LOOKUP.PT_Tent = ['18duo-off', '18duo-standby'];
    fade.TEXTURE_LOOKUP.PT_Durability = ['18duo-book', cover];
    const nativeResolveTexture = fade.resolveTexture.bind(fade);
    // Orbiting a tent view changes the camera's source label, but not which
    // physical display is active. Keep the inner screen off while tumbling.
    fade.resolveTexture = () => current.__duoSelectedState === 'PT_Tent'
      ? fade.TEXTURE_LOOKUP.PT_Tent : nativeResolveTexture();
    fade._lastTextureKeys = null;
    fade.applyScreenTextures(fade.TEXTURE_LOOKUP.PT_SliderLanding);
    return fade;
  }
  function installStateAdapter(current, fade) {
    const originalViewChange = current.onViewChange.bind(current);
    current.onViewChange = ({ item, index }) => {
      const requested = item?.dataset.viewerState || 'PT_SliderLanding';
      if (index === -1) { originalViewChange({ item, index }); redraw(); return; }
      if (current.__duoIntendedState && requested !== current.__duoIntendedState) return;
      current.__duoBook = requested === 'PT_Durability';
      current.onSliderInactive();
      originalViewChange({ item, index });
      current.setPtVariantWeights([{ label: requested, weight: 1 }], reducedMotion.matches);
      if (reducedMotion.matches) {
        current.ptActivation.value = current.ptActivation.targetValue = 1;
        current.ptActivation.velocity = 0;
      }
      current.__duoSelectedState = requested;
      notify(requested);
      redraw();
    };
  }
  function installFoldPlayback(current, host) {
    const fade = current.hinge.component.scripts.get('FadeThroughBlack');
    const resetButton = host.querySelector('.close-button');
    const button = document.createElement('button');
    button.className = 'duo-play-fold';
    button.type = 'button';
    const icon = '<svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m7 4 8 6-8 6V4Z" fill="currentColor"/></svg>';
    const stopIcon = '<svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><rect x="5" y="5" width="10" height="10" rx="2" fill="currentColor"/></svg>';
    resetButton.parentElement.prepend(button);
    let elapsed = 0, phase = 0, interacted = false;
    const phases = ['PT_SliderClosed', 'PT_SliderOpen', 'PT_SliderLanding'];
    // Each leg gives the authored hinge spring and fade-out/fade-in time to
    // reveal the shape. Geometry and camera motion use the native springs.
    const phaseDuration = () => current.hinge.data.springDuration + fade.fadeDuration * 2;
    const updateButton = () => {
      button.innerHTML = playback.isPlaying ? `${stopIcon}<span>停止演示</span>` : `${icon}<span>播放开合</span>`;
      button.setAttribute('aria-label', playback.isPlaying ? '停止开合演示' : '播放折叠手机的开合动画');
      button.setAttribute('aria-pressed', String(playback.isPlaying));
    };
    const retarget = label => {
      current.onSliderInactive();
      current.__duoIntendedState = current.__duoSelectedState = 'PT_SliderLanding';
      current.__duoBook = false;
      current._prevCameraPositionIndex = 0;
      current.ptActivation.targetValue = 1;
      current.setPtVariantWeights([{ label, weight: 1 }], false);
      redraw();
    };
    const playback = {
      isPlaying: false,
      stop() {
        if (!this.isPlaying) return;
        this.isPlaying = false;
        elapsed = 0;
        updateButton();
      },
      play() {
        if (reducedMotion.matches) { reset(); return; }
        this.stop();
        this.isPlaying = true;
        phase = 0;
        elapsed = 0;
        expandMobileState('PT_SliderLanding');
        notify('PT_SliderLanding');
        updateButton();
        retarget(phases[phase]);
      },
      startIntro() {
        if (!interacted && !reducedMotion.matches) this.play();
      },
    };
    current.__duoFoldPlayback = playback;
    current.eventPool.register({
      script: '18DuoFoldPlayback',
      onLoop({ delta }) {
        if (!playback.isPlaying) return;
        elapsed += delta;
        if (elapsed < phaseDuration()) return;
        elapsed = 0;
        phase++;
        if (phase === phases.length) playback.stop();
        else retarget(phases[phase]);
      },
    });
    button.addEventListener('click', event => {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (playback.isPlaying) playback.stop();
      else playback.play();
    });
    // Native pointer and keyboard handlers take over immediately, including
    // while a spring is moving. Stopping playback never snaps its values.
    for (const name of ['pointerdown', 'keydown']) {
      host.addEventListener(name, event => {
        interacted = true;
        if (!button.contains(event.target)) playback.stop();
      }, true);
    }
    const nativeHingeLoop = current.hinge.onLoop.bind(current.hinge);
    current.hinge.onLoop = event => {
      if (reducedMotion.matches && !current.hinge.sliderIsActive) {
        const state = current.hinge.simulator.currentState;
        if (state.position !== current.hinge.target || state.velocity !== 0) current.hinge.simulator.reset(current.hinge.target);
      }
      nativeHingeLoop(event);
    };
    const originalFadeDuration = fade.data.fadeDuration;
    const originalBounce = current.hinge.data.springBounce;
    const applyMotionPreference = () => {
      playback.stop();
      fade.data.fadeDuration = reducedMotion.matches ? .16 : originalFadeDuration;
      current.hinge.data.springBounce = reducedMotion.matches ? 0 : originalBounce;
      button.hidden = reducedMotion.matches;
      redraw();
    };
    reducedMotion.addEventListener('change', applyMotionPreference);
    applyMotionPreference();
    updateButton();
    window.DuoViewer.play = () => playback.play();
    window.DuoViewer.stop = () => playback.stop();
    return playback;
  }
  function nearestSkin(screen, position) {
    const a = screen.geometry.attributes;
    let closest = 0, distance = Infinity;
    for (let i = 0; i < a.position.count; i++) {
      const d = (a.position.getX(i) - position[0]) ** 2 + (a.position.getY(i) - position[1]) ** 2 + (a.position.getZ(i) - position[2]) ** 2;
      if (d < distance) { distance = d; closest = i; }
    }
    return { indexes: [a.skinIndex.getX(closest), a.skinIndex.getY(closest), a.skinIndex.getZ(closest), a.skinIndex.getW(closest)], weights: [a.skinWeight.getX(closest), a.skinWeight.getY(closest), a.skinWeight.getZ(closest), a.skinWeight.getW(closest)] };
  }
  function cameraGlassTexture(kind) {
    const T = window.Lotus.THREE;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 128;
    const context = canvas.getContext('2d');
    // Product photos show a black aperture, fine edge and subdued lens glass.
    // The flexible inner display has a darker, less pronounced edge.
    const edge = context.createRadialGradient(64, 64, 0, 64, 64, 64);
    const stops = kind === 'cover'
      ? [[0, '#080b0d'], [.73, '#050709'], [.88, '#090b0e'], [.94, '#34383b'], [1, '#090b0d']]
      : [[0, '#080e12'], [.76, '#040608'], [.9, '#080b0f'], [.96, '#1c242c'], [1, '#06080a']];
    for (const [offset, color] of stops) edge.addColorStop(offset, color);
    context.fillStyle = edge; context.fillRect(0, 0, 128, 128);
    const lens = context.createRadialGradient(60, 60, 0, 64, 64, 23);
    lens.addColorStop(0, kind === 'cover' ? '#101f27' : '#0b1b27');
    lens.addColorStop(.45, '#080e14'); lens.addColorStop(1, '#050709');
    context.fillStyle = lens; context.beginPath(); context.arc(64, 64, 23, 0, Math.PI * 2); context.fill();
    const glint = context.createRadialGradient(57, 56, 0, 57, 56, 9);
    glint.addColorStop(0, kind === 'cover' ? 'rgba(93,116,132,.38)' : 'rgba(65,108,135,.3)');
    glint.addColorStop(1, 'rgba(8,14,20,0)');
    context.fillStyle = glint; context.fillRect(42, 41, 30, 30);
    const texture = new T.CanvasTexture(canvas);
    texture.colorSpace = T.SRGBColorSpace;
    return texture;
  }
  function addPunchHole(screen, name, position, radius, kind) {
    const T = window.Lotus.THREE;
    const geometry = new T.CircleGeometry(radius, 48);
    geometry.rotateX(Math.PI / 2);
    geometry.translate(...position);
    const skin = nearestSkin(screen, position);
    const count = geometry.attributes.position.count;
    const indexes = new Uint16Array(count * 4), weights = new Float32Array(count * 4);
    for (let i = 0; i < count; i++) { indexes.set(skin.indexes, i * 4); weights.set(skin.weights, i * 4); }
    geometry.setAttribute('skinIndex', new T.Uint16BufferAttribute(indexes, 4));
    geometry.setAttribute('skinWeight', new T.Float32BufferAttribute(weights, 4));
    const material = new T.MeshBasicMaterial({ map: cameraGlassTexture(kind), side: T.DoubleSide, toneMapped: false, depthWrite: false });
    const mesh = new T.SkinnedMesh(geometry, material);
    mesh.name = name;
    mesh.bind(screen.skeleton, screen.bindMatrix.clone());
    mesh.bindMode = screen.bindMode;
    mesh.layers.set(5);
    mesh.renderOrder = 10;
    mesh.frustumCulled = false;
    screen.parent.add(mesh);
    return mesh;
  }
  function closeOriginalCameraOpenings(screen) {
    const T = window.Lotus.THREE;
    const geometry = screen.geometry;
    const positions = geometry.attributes.position;
    const indexes = geometry.index ? [...geometry.index.array] : Array.from({ length: positions.count }, (_, i) => i);
    const edges = new Map();
    const vertices = new Map();
    const key = i => [positions.getX(i), positions.getY(i), positions.getZ(i)]
      .map(value => Math.round(value * 10000)).join(',');
    for (let i = 0; i < indexes.length; i += 3) {
      for (const [a, b] of [[indexes[i], indexes[i + 1]], [indexes[i + 1], indexes[i + 2]], [indexes[i + 2], indexes[i]]]) {
        const ka = key(a), kb = key(b);
        vertices.set(ka, a);
        vertices.set(kb, b);
        const id = [ka, kb].sort().join('|');
        const edge = edges.get(id) || { a, b, ka, kb, count: 0 };
        edge.count++;
        edges.set(id, edge);
      }
    }
    const boundaries = [...edges.values()].filter(edge => edge.count === 1);
    const neighbors = new Map();
    for (const { ka, kb } of boundaries) {
      for (const [from, to] of [[ka, kb], [kb, ka]]) {
        if (!neighbors.has(from)) neighbors.set(from, []);
        neighbors.get(from).push(to);
      }
    }
    const visited = new Set();
    const holes = [];
    for (const first of neighbors.keys()) {
      if (visited.has(first)) continue;
      const stack = [first], loop = [];
      while (stack.length) {
        const vertex = stack.pop();
        if (visited.has(vertex)) continue;
        visited.add(vertex);
        loop.push(vertex);
        stack.push(...neighbors.get(vertex));
      }
      const xs = loop.map(id => positions.getX(vertices.get(id)));
      const zs = loop.map(id => positions.getZ(vertices.get(id)));
      // Fill only the small camera boundaries; preserve the screen's outer edge.
      if (loop.length >= 8 && Math.max(...xs) - Math.min(...xs) < 1.5 && Math.max(...zs) - Math.min(...zs) < 1.5) holes.push(loop);
    }
    if (!holes.length) return 0;
    // Add triangles to the actual screen. They share its shader, UV coordinates,
    // skeleton and exposure, so no overlay disk remains during folds or fades.
    for (const [name, attribute] of Object.entries(geometry.attributes)) {
      const array = new attribute.array.constructor((attribute.count + holes.length) * attribute.itemSize);
      array.set(attribute.array);
      holes.forEach((keys, h) => {
        const loop = keys.map(id => vertices.get(id));
        for (let c = 0; c < attribute.itemSize; c++) {
          const value = /skinIndex|joints/.test(name)
            ? attribute.array[loop[0] * attribute.itemSize + c]
            : loop.reduce((sum, i) => sum + attribute.array[i * attribute.itemSize + c], 0) / loop.length;
          array[(attribute.count + h) * attribute.itemSize + c] = value;
        }
      });
      geometry.setAttribute(name, new T.BufferAttribute(array, attribute.itemSize, attribute.normalized));
    }
    holes.forEach((loop, h) => {
      const members = new Set(loop);
      for (const { a, b, ka, kb } of boundaries) if (members.has(ka) && members.has(kb)) indexes.push(b, a, positions.count + h);
    });
    geometry.setIndex(indexes);
    geometry.computeBoundingBox();
    geometry.computeBoundingSphere();
    return holes.length;
  }
  function installFrontCameras(current) {
    const file = current.hinge.component.element;
    const inner = file.getObjectByName('skeleton_0_3_screenTexture_geo');
    const cover = file.getObjectByName('skeleton_0_7_outerDisplayScreenTexture_geo');
    // These four source meshes belong only to the old corner selfie camera.
    // Keep the reference hinge, body and rear-camera geometry intact.
    const oldCoverCamera = ['ehFPMznSGbmsGvH', 'vCDzJvgfRHxZtCX', 'ngETxiQNpkmnIgB', 'CDcRBIEJOCUrJBF'];
    for (const name of oldCoverCamera) {
      const mesh = file.getObjectByName(name);
      if (mesh) { mesh.visible = false; mesh.parent.remove(mesh); }
    }
    current.__duoCameraCaps = { inner: closeOriginalCameraOpenings(inner), cover: closeOriginalCameraOpenings(cover) };
    // Source geometry is landscape: (+X, -Z) becomes the upper-left corner
    // when the native portrait preset rotates the unfolded device.
    // Visual fit to official front renders and the IT Home close-up, rather
    // than claimed hardware measurements. Cover aperture sits in the top rail.
    const innerPosition = [7.29, 0.261, -5.02];
    const coverPosition = [-4.099949, -0.286, -5.16];
    const innerRadius = 0.155, coverRadius = 0.185;
    addPunchHole(inner, '18duo-inner-punch-hole', innerPosition, innerRadius, 'inner');
    addPunchHole(cover, '18duo-cover-punch-hole', coverPosition, coverRadius, 'cover');
    current.__duoFrontCameras = { innerPosition, coverPosition, innerRadius, coverRadius, oldCoverCamera };
  }
  function closeOriginalBackMark(shell) {
    const T = window.Lotus.THREE;
    const geometry = shell.geometry, positions = geometry.attributes.position;
    const indexes = [...geometry.index.array];
    const edges = new Map();
    const key = i => [positions.getX(i), positions.getY(i), positions.getZ(i)]
      .map(value => Math.round(value * 10000)).join(',');
    for (let i = 0; i < indexes.length; i += 3) {
      for (const [a, b] of [[indexes[i], indexes[i + 1]], [indexes[i + 1], indexes[i + 2]], [indexes[i + 2], indexes[i]]]) {
        const ka = key(a), kb = key(b), id = [ka, kb].sort().join('|');
        const edge = edges.get(id) || { a, b, ka, kb, count: 0 };
        edge.count++;
        edges.set(id, edge);
      }
    }
    // The original metal shell has two cutouts for the logo body and leaf.
    // Close only their boundaries with the existing UVs and skin weights.
    const inMark = i => positions.getX(i) > 3.1 && positions.getX(i) < 5.1
      && Math.abs(positions.getZ(i)) < 1.2 && Math.abs(positions.getY(i) + .2767) < .001;
    const neighbors = new Map();
    for (const { a, b, ka, kb, count } of edges.values()) {
      if (count !== 1 || !inMark(a) || !inMark(b)) continue;
      for (const [from, to, i] of [[ka, kb, a], [kb, ka, b]]) {
        if (!neighbors.has(from)) neighbors.set(from, { i, next: [] });
        neighbors.get(from).next.push(to);
      }
    }
    const visited = new Set();
    let closed = 0;
    for (const first of neighbors.keys()) {
      if (visited.has(first)) continue;
      let previous = null, vertex = first;
      const loop = [];
      do {
        if (visited.has(vertex) || neighbors.get(vertex).next.length !== 2) break;
        visited.add(vertex);
        loop.push(neighbors.get(vertex).i);
        const next = neighbors.get(vertex).next.find(value => value !== previous);
        previous = vertex; vertex = next;
      } while (vertex && vertex !== first);
      if (vertex !== first || loop.length < 3) continue;
      const points = loop.map(i => new T.Vector2(positions.getX(i), positions.getZ(i)));
      for (const triangle of T.ShapeUtils.triangulateShape(points, [])) {
        const [a, b, c] = triangle.map(i => loop[i]);
        const cross = (positions.getX(b) - positions.getX(a)) * (positions.getZ(c) - positions.getZ(a))
          - (positions.getZ(b) - positions.getZ(a)) * (positions.getX(c) - positions.getX(a));
        indexes.push(a, ...(cross > 0 ? [b, c] : [c, b]));
      }
      closed++;
    }
    geometry.setIndex(indexes);
    // Reconstruct the plate's existing occlusion gradient across the old
    // logo from clean samples on both sides. Matching this gradient avoids
    // a lighter rectangular patch under the replacement mark.
    const material = shell.material, compile = material.onBeforeCompile.bind(material);
    material.onBeforeCompile = (shader, renderer) => {
      compile(shader, renderer);
      shader.vertexShader = 'varying vec3 vDuoRearRestPosition;\n' + shader.vertexShader;
      shader.vertexShader = shader.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\nvDuoRearRestPosition = position;');
      shader.fragmentShader = 'varying vec3 vDuoRearRestPosition;\n' + shader.fragmentShader;
      const ao = T.ShaderChunk.aomap_fragment.replace('reflectedLight.indirectDiffuse *= ambientOcclusion;', `
        vec2 duoBrandDistance = abs(vDuoRearRestPosition.xz - vec2(4.09995, 0.07645)) / vec2(1.1, 1.3);
        float duoBrandMask = 1.0 - smoothstep(0.9, 1.0, max(duoBrandDistance.x, duoBrandDistance.y));
        float duoPlateOcclusion = mix(
          texture2D(aoMap, vec2(0.33, vAoMapUv.y)).r,
          texture2D(aoMap, vec2(0.65, vAoMapUv.y)).r,
          clamp((vAoMapUv.x - 0.33) / 0.32, 0.0, 1.0));
        float duoPlateAmbient = (duoPlateOcclusion - 1.0) * aoMapIntensity + 1.0;
        ambientOcclusion = mix(ambientOcclusion, duoPlateAmbient, duoBrandMask);
        reflectedLight.indirectDiffuse *= ambientOcclusion;`);
      shader.fragmentShader = shader.fragmentShader.replace('#include <aomap_fragment>', ao);
    };
    material.customProgramCacheKey = () => '18duo-rear-brand-gradient';
    material.needsUpdate = true;
    return closed;
  }
  function installBackBrand(current) {
    const T = window.Lotus.THREE;
    const file = current.hinge.component.element;
    const original = file.getObjectByName('LDcHeENovRXWVxD');
    const markParent = original.parent;
    const position = [4.09995, -0.279, 0.07645];
    const skin = nearestSkin(original, position);
    const closedCutouts = closeOriginalBackMark(file.getObjectByName('CinrmKreKeCYatm'));
    for (const name of ['LDcHeENovRXWVxD', 'ZEAwVPmUDdMbViq']) {
      const mesh = file.getObjectByName(name);
      if (mesh) { mesh.visible = false; mesh.geometry.setDrawRange(0, 0); mesh.parent.remove(mesh); }
    }
    // Monochrome Mi glyphs from Xiaomi's official global-site logo SVG.
    // The source logo's alternate glyphs are offset by 112 in its animation.
    const canvas = document.createElement('canvas');
    canvas.width = 512; canvas.height = 326;
    const context = canvas.getContext('2d');
    context.scale(512 / 75, 326 / 48);
    context.translate(-129, -32);
    context.fillStyle = '#a4a4a0';
    context.fill(new Path2D('M200.6006 34.56C200.9866 34.56 201.3066 34.87 201.3066 35.252V76.754C201.3066 77.13 200.9866 77.442 200.6006 77.442H191.5066C191.1166 77.442 190.8006 77.13 190.8006 76.754V35.252C190.8006 34.87 191.1166 34.56 191.5066 34.56ZM161.1406 34.56C168.0006 34.56 175.1726 34.874 178.7086 38.414C182.188558 41.897875 182.564028 48.8333125 182.578162 55.5684076L182.5786 76.754C182.5786 77.13 182.2626 77.442 181.8726 77.442H172.7826C172.3926 77.442 172.0746 77.13 172.0746 76.754V55.204C172.0646 51.442 171.8486 47.576 169.9086 45.63C168.2386 43.956 165.1226 43.572 161.8826 43.492H145.4026C145.0146 43.492 144.6986 43.804 144.6986 44.18V76.754C144.6986 77.13 144.3786 77.442 143.9906 77.442H134.8926C134.5046 77.442 134.1906 77.13 134.1906 76.754V35.252C134.1906 34.87 134.5046 34.56 134.8926 34.56ZM163.1766 51.088C163.5626 51.088 163.8766 51.398 163.8766 51.776V76.754C163.8766 77.13 163.5626 77.442 163.1766 77.442H153.6246C153.2326 77.442 152.9166 77.13 152.9166 76.754V51.776C152.9166 51.398 153.2326 51.088 153.6246 51.088Z'));
    const texture = new T.CanvasTexture(canvas);
    texture.colorSpace = T.SRGBColorSpace;
    texture.center.set(.5, .5);
    texture.rotation = Math.PI;
    const geometry = new T.PlaneGeometry(1.32, .84);
    geometry.rotateX(Math.PI / 2);
    geometry.translate(...position);
    const count = geometry.attributes.position.count;
    const indexes = new Uint16Array(count * 4), weights = new Float32Array(count * 4);
    for (let i = 0; i < count; i++) { indexes.set(skin.indexes, i * 4); weights.set(skin.weights, i * 4); }
    geometry.setAttribute('skinIndex', new T.Uint16BufferAttribute(indexes, 4));
    geometry.setAttribute('skinWeight', new T.Float32BufferAttribute(weights, 4));
    const material = new T.MeshBasicMaterial({ map: texture, transparent: true, alphaTest: .05, depthWrite: false, toneMapped: false });
    const mark = new T.SkinnedMesh(geometry, material);
    mark.name = '18duo-xiaomi-back-logo';
    mark.bind(original.skeleton, original.bindMatrix.clone());
    mark.bindMode = original.bindMode;
    mark.layers.mask = original.layers.mask;
    mark.renderOrder = 2;
    mark.frustumCulled = false;
    markParent.add(mark);
    current.__duoBackBrand = { name: mark.name, removed: ['LDcHeENovRXWVxD', 'ZEAwVPmUDdMbViq'], position, closedCutouts };
  }
  function installBodyFinish(current) {
    const T = window.Lotus.THREE, file = current.hinge.component.element;
    // Small, repeatable normal and roughness variations give the coating a
    // fine bead-blasted finish. Mipmaps keep the grain stable during orbit.
    const size = 128, normalData = new Uint8Array(size * size * 4), roughnessData = new Uint8Array(size * size * 4);
    let seed = 18;
    const random = () => {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      return (seed >>> 24) / 255;
    };
    for (let i = 0; i < size * size; i++) {
      const x = Math.round(128 + (random() - .5) * 10), y = Math.round(128 + (random() - .5) * 10);
      const roughness = Math.round(226 + random() * 29);
      normalData.set([x, y, 255, 255], i * 4);
      roughnessData.set([roughness, roughness, roughness, 255], i * 4);
    }
    const normalMap = new T.DataTexture(normalData, size, size);
    const roughnessMap = new T.DataTexture(roughnessData, size, size);
    normalMap.name = '18duo-titanium-micrograin';
    roughnessMap.name = '18duo-titanium-satin-roughness';
    for (const texture of [normalMap, roughnessMap]) {
      texture.wrapS = texture.wrapT = T.RepeatWrapping;
      texture.repeat.set(2, 2);
      texture.magFilter = T.LinearFilter;
      texture.minFilter = T.LinearMipmapLinearFilter;
      texture.generateMipmaps = true;
      texture.needsUpdate = true;
    }
    // Explicit source parts keep this finish on the housing, hinge and
    // metal controls. Retain all source geometry and material shader hooks.
    const groups = [
      { color: '#612a36', roughness: .69, metalness: .82, names: [
        'CinrmKreKeCYatm', 'pOKbukdBaFJnrfD', 'RfBGHMFerLjISPN', 'nQVCQjoYoIWfEsS'
      ] },
      { color: '#773c48', roughness: .48, metalness: .9, names: [
        'zoomXpAIrYQzugW', 'gOyRRXvnAmbVqWL', 'cvBbKnuJcrIBZuu', 'iRznfsyqhvoDDyt',
        'rQxGZsmznrzUeYt', 'jVNOUobItbiDoDD', 'zLXRXGVoVtYNflo', 'WEYnCjqQSsCImAQ',
        'PGxpbMakmsDzhPc', 'oSZhoRoDpKfKcBw', 'UnANvSAbryWRcbh', 'swQUpNanvbPiFhv',
        'cRuITaYAPMcqUYi', 'mfXiWudhZUrKgyi', 'MIVewVdhkFIvrnF'
      ] },
      { color: '#773c48', roughness: .48, metalness: .9, names: [
        'MjAVumOaiYuioav', 'ZPNsoZZFjYgtJqv', 'KhyqIJqdxTastBJ', 'okvaRZKSXDChOFt'
      ] }
    ];
    const finished = new Set();
    for (const group of groups) {
      for (const name of group.names) {
        const material = file.getObjectByName(name).material;
        if (finished.has(material)) continue;
        material.color.set(group.color);
        material.roughness = group.roughness;
        material.metalness = group.metalness;
        material.normalMap = normalMap;
        material.normalScale.set(.65, .65);
        material.roughnessMap = roughnessMap;
        material.metalnessMap = null;
        material.clearcoat = .035;
        material.clearcoatRoughness = .7;
        material.needsUpdate = true;
        finished.add(material);
      }
    }
    current.__duoBodyFinish = { name: '西野红 · 酒红磨砂钛金属', groups, materialCount: finished.size };
  }
  function installEmbeddedViewport(current) {
    const T = window.Lotus.THREE;
    // Apply one fixed viewport transform to the native camera projection.
    // The reference camera still owns its position, rotation and zoom. Never
    // refit to live screen bounds: that recentred/rescaled the folding motion.
    let viewportFit = { scale: 1, shiftX: 0 };
    const fitMatrix = new T.Matrix4();
    const updateViewportFit = () => {
      const { width, height } = document.querySelector('.product-viewer-canvas').getBoundingClientRect();
      if (innerWidth <= 734) {
        viewportFit = { scale: Math.min(1, width * .86 / (height * .92)), shiftX: 0 };
      } else {
        const controls = document.querySelector('.controls').getBoundingClientRect();
        const expandedWidth = parseFloat(getComputedStyle(document.querySelector('.all-access-pass--product-viewer')).getPropertyValue('--aap-expanded-width'));
        const left = controls.left + (expandedWidth || controls.width) + 40;
        const right = width - 50;
        viewportFit = { scale: Math.min(1, (right - left) / (height * .92)), shiftX: (left + right) / width - 1 };
      }
      fitMatrix.set(viewportFit.scale, 0, 0, viewportFit.shiftX, 0, viewportFit.scale, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
    };
    updateViewportFit();
    new ResizeObserver(updateViewportFit).observe(document.querySelector('.product-viewer-canvas'));
    current.__duoViewportFit = () => ({ ...viewportFit });
    for (const component of current.components.list) {
      const camera = component.scripts.get('CameraMixerOffset');
      if (!camera) continue;
      const originalLoop = camera.onLoop.bind(camera);
      camera.onLoop = (...args) => {
        originalLoop(...args);
        // The embedded mobile layout does not traverse the camera's parent
        // hierarchy on every frame. Commit its new pose before rendering.
        camera.updateMatrix();
        camera.updateMatrixWorld(true);

      };
    }
    const originalRenderScene = current.renderer.render.bind(current.renderer);
    current.renderer.render = (renderScene, camera) => {
      if (camera === current.camera && renderScene.getObjectByName?.('skeleton_0_3_screenTexture_geo')) {
        // CameraMixerOffset may reuse its camera between frames. Rebuild the
        // native projection before applying the fixed embedding transform.
        camera.updateProjectionMatrix();
        camera.projectionMatrix.premultiply(fitMatrix);
        camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert();
      }
      return originalRenderScene(renderScene, camera);
    };
  }
  function installHingeScreenWipe(current) {
    const records = [];
    for (const name of ['skeleton_0_3_screenTexture_geo', 'skeleton_0_7_outerDisplayScreenTexture_geo']) {
      const material = current.hinge.component.element.getObjectByName(name).material;
      const nativeRender = material.onBeforeRender.bind(material);
      const wipe = current.hinge.component.chunks[material.name].Wipe;
      // The reference textures are 2670px wide. Keep our 1800px screenshot
      // at its native size so the clear panel is not downsampled twice.
      wipe.renderTargetScale = 1;
      const record = { name, passes: 0, amount: 0 };
      records.push(record);
      material.onBeforeRender = (...args) => {
        // The source Wipe owns FramePass, both BlurPass stages and the
        // projected screen UV. They must be enabled as one complete chain.
        const enabled = !reducedMotion.matches && current.__duoSelectedState === 'PT_SliderLanding';
        material.uniforms.enableFraming.value = enabled;
        nativeRender(...args);
        record.amount = enabled ? material.uniforms.wipeAmount.value : 0;
        if (enabled) record.passes += 2;
      };
    }
    current.__duoScreenWipe = records;
  }

function installDemandLoop(current) {
  const engine = window.Lotus.Lotus;
  const loop = current.loop;
  const emitter = loop.emitter;
  const nativeRun = emitter.run.bind(emitter);
  const nativeUpdate = loop.onUpdate;
  let inTick = false, requested = false, visible = true, sleeping = false;
  const fade = current.hinge.component.scripts.get('FadeThroughBlack');
  const stats = { updates: 0, requests: 0 };
  const movingSpring = spring => spring && (spring.active || spring.value !== spring.targetValue || spring.velocity !== 0);
  // Native onFrame replaces its observable weights array even at rest. Skip
  // that write only when all native springs are at their own rest thresholds.
  for (const script of current.eventPool.instances) {
    if (script.script !== 'VariantWeights') continue;
    const onFrame = script.onFrame;
    script.onFrame = function (event) {
      const settled = this.springs?.length && this.springs.every(spring => !movingSpring(spring));
      const committed = this.namedWeights.every((weight, index) => weight.weight === this.springs[index].value);
      if (settled && committed) return;
      onFrame.call(this, event);
      // Native orbit-to-preset selection can snap spring values before the
      // observable weights are committed. Publish that final state once.
      if (settled && !committed) loop.tryRequestAnimationFrame();
    };
  }
  const simulatorMoving = () => {
    const simulation = current.hinge.simulator;
    const state = simulation.currentState;
    const epsilon = simulation.GLOBAL_THRESHOLD;
    return Math.abs(state.position - state.target) >= epsilon || Math.abs(state.velocity) >= epsilon ||
      Math.abs(state.stretchPosition - state.stretchTarget) >= epsilon || Math.abs(state.stretchVelocity) >= epsilon;
  };
  const active = () => {
    if (current.__duoFoldPlayback?.isPlaying || requested || loop.shouldDrawFrame || engine.tasks.list.length || fade._exposureActive || fade._exposureTo < 0 || simulatorMoving()) return true;
    for (const instance of current.eventPool.instances) {
      if (instance.springs?.some(spring => movingSpring(spring)) || movingSpring(instance.mixSpring) || instance.clock?.running) return true;
    }
    return Boolean(movingSpring(current.ptActivation));
  };
  const wake = () => { if (visible && !document.hidden) { if (sleeping) loop.clock.getDelta(); sleeping = false; nativeRun(); } };
  // Requests made during onLoop survive native cancelAnimationFrame(), which
  // otherwise clears the current draw flag at the end of onRender.
  loop.tryRequestAnimationFrame = function () {
    stats.requests++;
    requested = true;
    loop.shouldDrawFrame = true;
    wake();
  };
  emitter.run = () => { if (!inTick || active()) wake(); else sleeping = true; };
  emitter.off('update', nativeUpdate);
  const update = () => {
    if (!visible || document.hidden) return;
    if (active()) loop.shouldDrawFrame = true;
    requested = false;
    inTick = true;
    stats.updates++;
    try { nativeUpdate(); }
    finally { inTick = false; if (requested) loop.shouldDrawFrame = true; }
  };
  emitter.on('update', update);
  const debuggerTextures = window.Lotus.TextureDebugger;
  debuggerTextures.addTexture = () => {};
  debuggerTextures.textures.splice(0);
  const setVisible = value => {
    visible = value;
    if (!visible || document.hidden) { emitter.cancel(); current.hinge.simulator.RAF.cancel(); }
    else { loop.clock.getDelta(); loop.tryRequestAnimationFrame(); }
  };
  document.addEventListener('visibilitychange', () => setVisible(visible));
  current.__duoDemandLoop = { stats, active, setVisible };
  loop.tryRequestAnimationFrame();
}

  function installFallback(host) {
    const images = { PT_SliderLanding: screenFiles.inner, PT_Landscape: screenFiles.inner, PT_Portrait: 'home-portrait.webp', PT_Closed: 'home-cover.webp', PT_Laptop: screenFiles.split, PT_Tent: 'standby-calendar.webp', PT_Durability: 'book-mode.webp' };
    const poster = host.querySelector('.duo-poster img');
    const choose = state => {
      expandMobileState(state);
      poster.src = new URL(images[state], screenshotRoot).href;
      poster.alt = `18Duo ${stateNames[state]}真实界面`;
      notify(state);
    };
    host.addEventListener('click', event => {
      const button = event.target.closest('.control-item-open');
      if (!button) return;
      event.preventDefault(); event.stopImmediatePropagation();
      choose(button.closest('.control-item').dataset.viewerState);
    }, true);
    window.DuoViewer.select = choose;
    window.DuoViewer.reset = () => choose('PT_SliderLanding');
    choose('PT_SliderLanding');
  }
  async function initialize() {
    const host = document.querySelector('.product-viewer-component');
    const applyLayout = height => {
      if (Number.isFinite(height) && height >= 350 && height <= 1800) {
        document.documentElement.style.setProperty('--viewer-height', `${Math.floor(height)}px`);
      }
      document.querySelector('.all-access-pass--product-viewer').style.setProperty('--aap-expanded-width', `${Math.min(innerWidth * .37, 360)}px`);
    };
    applyLayout(window.frameElement?.getBoundingClientRect().height || innerHeight);
    const fitStage = () => {
      if (innerWidth > 734) return;
      const top = host.querySelector('.controls').getBoundingClientRect().top;
      document.documentElement.style.setProperty('--stage-height', `${Math.max(90, Math.floor(top - 12))}px`);
    };
    new ResizeObserver(fitStage).observe(host.querySelector('.controls'));
    window.addEventListener('resize', fitStage);
    window.addEventListener('message', event => {
      if (event.origin === location.origin && event.source === parent && event.data?.type === '18duo-viewer-layout') {
        applyLayout(Number(event.data.height));
      }
    });
    window.addEventListener('resize', () => applyLayout(innerHeight));
    const status = document.createElement('div');
    status.className = 'duo-status';
    status.setAttribute('role', 'status');
    status.innerHTML = '<span>正在准备交互展示，先看看真实桌面</span>';
    host.append(status);
    const sendHeight = () => {
      if (parent !== window) parent.postMessage({ type: '18duo-viewer-resize',
        height: Math.ceil(host.getBoundingClientRect().height + 20) }, location.origin);
    };
    new ResizeObserver(sendHeight).observe(host);
    // Programmatic focus in the embedded tour must not scroll the host page.
    host.querySelectorAll('[data-focus-expression]').forEach(element => element.removeAttribute('data-focus-expression'));
    host.querySelectorAll('.control-item-content, .control-item-content-mask, .control-detent-slider--input').forEach(element => {
      element.focus = options => HTMLElement.prototype.focus.call(element, { ...options, preventScroll: true });
    });
    const reset = host.querySelector('.close-button');
    host.append(reset.parentElement);
    reset.title = '复位展示';
    reset.setAttribute('aria-label', '复位展示');
    reset.innerHTML = '<span aria-hidden="true" style="font-size:24px;line-height:1">↺</span>';
    reset.addEventListener('click', event => { event.preventDefault(); event.stopImmediatePropagation(); window.DuoViewer.reset(); }, true);
    try {
      if (!window.DUO_VIEWER_WEBGL) throw new Error('此浏览器未启用 WebGL 2');
      // The native mobile carousel waits for its first interaction.
      // Trigger that load event for the embedded controls.
      if (innerWidth <= 734) {
        for (let attempt=0; attempt<120; attempt++) {
          const singleton=window.__duoRequire?.('ApY4q/z+mDxRKgUHvbiF')?.ProductViewerWebGLSingleton;
          if (singleton?.anim) { singleton.anim.trigger('product-viewer:trigger-load'); break; }
          await waitFrame();
        }
      }
      const current = await ready();
      const fade = await installScreens(current);
      installStateAdapter(current, fade);
      installFrontCameras(current);
      installBackBrand(current);
      installBodyFinish(current);
      installEmbeddedViewport(current);
      installHingeScreenWipe(current);
      installMobileControls(host);
      installSliderSynchronization(current, host);
      const playback = installFoldPlayback(current, host);
      // The reference full page swaps to a static gallery at its breakpoint.
      // Keep this embedded 3D scene alive across a host viewport resize.
      const viewer = singleton(), nativeDestroy = viewer.destroy.bind(viewer);
      viewer.destroy = () => { if (!host.isConnected) nativeDestroy(); };
      const keepEnhanced = () => {
        const html = document.documentElement;
        if (html.classList.contains('enhanced') && html.classList.contains('enhanced-rt')) return;
        html.classList.remove('no-enhanced', 'no-enhanced-rt');
        html.classList.add('enhanced', 'enhanced-rt');
      };
      const features = new MutationObserver(keepEnhanced);
      features.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
      installDemandLoop(current);
      const observe = new IntersectionObserver(entries => current.__duoDemandLoop.setVisible(entries[0].isIntersecting));
      observe.observe(host);
      window.addEventListener('message', event => {
        if (event.origin === location.origin && event.source === parent && event.data?.type === '18duo-viewer-visibility') {
          current.__duoDemandLoop.setVisible(Boolean(event.data.visible));
        }
      });
      await current.onPointerStart();
      current.onPointerEnd();
      current.stopIntroTimer();
      current.timelines.get('intro')?.pause();
      host.querySelector('.product-viewer-canvas').classList.remove('entice');
      window.DuoViewer.reset();
      host.classList.remove('keyboard-nav');
      document.documentElement.classList.remove('duo-loading');
      status.hidden = true;
      redraw();
      window.DuoViewer.isReady = true;
      sendHeight();
      // Let the first visible frame appear before the one-time fold tour.
      requestAnimationFrame(() => requestAnimationFrame(() => playback.startIntro()));
      if (window.parent !== window) window.parent.postMessage({ type: '18duo-viewer-ready' }, window.location.origin);
    } catch (error) {
      status.innerHTML = '<span>当前浏览器显示真实界面截图</span>';
      host.classList.add('duo-fallback');
      installFallback(host);
      document.documentElement.classList.remove('duo-loading');
      if (window.parent !== window) window.parent.postMessage({ type: '18duo-viewer-error' }, window.location.origin);
      console.error(error);
    }
  }
  window.DuoViewer = { ready, scene, select, fold, reset, redraw, states: labels, isReady: false };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
  else initialize();
})();
