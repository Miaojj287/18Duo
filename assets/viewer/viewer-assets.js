/* Lossless transport for the viewer's own model and scene requests. */
(() => {
  'use strict';
  if (typeof DecompressionStream !== 'function') return;
  const nativeFetch = window.fetch.bind(window);
  const root = new URL('./v/', document.baseURI);
  window.fetch = async (input, options) => {
    const url = new URL(input instanceof Request ? input.url : input, document.baseURI);
    const method = options?.method || (input instanceof Request ? input.method : 'GET');
    if (method !== 'GET' || url.origin !== root.origin || !url.pathname.startsWith(root.pathname) || !/\.(bin|lsd|gltf)$/.test(url.pathname)) {
      return nativeFetch(input, options);
    }
    const packed = new URL(url);
    packed.pathname += '.gz';
    try {
      const request = input instanceof Request ? new Request(packed, input) : packed;
      const response = await nativeFetch(request, options);
      if (!response.ok) return nativeFetch(input, options);
      const bytes = await response.arrayBuffer();
      const signature = new Uint8Array(bytes, 0, Math.min(2, bytes.byteLength));
      // A host may already decode Content-Encoding: gzip. Decode only a gzip body.
      let body;
      if (signature[0] === 0x1f && signature[1] === 0x8b) {
        body = await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer();
      } else if (/gzip/i.test(response.headers.get('content-encoding') || '')) {
        body = bytes;
      } else {
        throw new Error('Invalid compressed viewer asset');
      }
      const headers = new Headers(response.headers);
      headers.delete('content-encoding');
      headers.delete('content-length');
      headers.set('content-type', url.pathname.endsWith('.bin') ? 'application/octet-stream' : 'application/json');
      return new Response(body, { status: response.status, statusText: response.statusText, headers });
    } catch (error) {
      if (error.name === 'AbortError' || options?.signal?.aborted || (input instanceof Request && input.signal.aborted)) throw error;
      return nativeFetch(input, options);
    }
  };
})();
