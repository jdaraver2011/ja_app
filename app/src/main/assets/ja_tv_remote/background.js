// Opt-in, read-only media request diagnostics for an active IPTV test session.
// Full request URLs remain transient in memory and are never written to logs.
(() => {
  'use strict';
  let nativePort = null;
  let reconnectTimer = null;
  let inspecting = false;
  let requestHeaderInspectionAvailable = false;
  const trackedRequests = new Set();
  const MAX_TRACKED_REQUESTS = 96;

  const classify = (raw, mime = '', resourceType = '') => {
    let url;
    try { url = new URL(raw); } catch (_) { return null; }
    if (!['http:', 'https:'].includes(url.protocol)) return null;
    const path = url.pathname.toLowerCase();
    const type = String(mime || '').toLowerCase();
    if (/\.m3u8?$/.test(path) || /mpegurl|vnd\.apple\.mpegurl/.test(type) ||
        /(?:^|[?&])(?:format|type)=m3u8(?:&|$)/i.test(url.search)) return 'HLS';
    if (/\.mpd$/.test(path) || /dash\+xml/.test(type) ||
        /(?:^|[?&])(?:format|type)=mpd(?:&|$)/i.test(url.search)) return 'DASH';
    if (/\.key$/.test(path) || /\/(?:keys?|aes|hls-?key)\//i.test(path)) return 'KEY';
    if (/\.(?:mp4|m4v|mov|webm|mkv|mp3|m4a|aac|ogg|opus|wav)(?:$|\.)/.test(path) ||
        /^(?:video|audio)\//.test(type)) return 'MEDIA';
    if (/\.(?:m4s|ts|cmfv|cmfa|aac)(?:$|\.)/.test(path)) return 'SEGMENT';
    if (resourceType === 'media') return 'MEDIA';
    if (resourceType === 'sub_frame') return 'IFRAME';
    return null;
  };
  const sendNative = message => {
    try { nativePort?.postMessage(message); } catch (_) {}
  };
  const requestDetails = (event, details, extra = {}) => {
    if (!details || !details.requestId) return;
    const kind = classify(details.url, extra.mime || '', details.type || '');
    if (!kind && event === 'MEDIA_REQUEST_CANDIDATE') return;
    if (kind) {
      if (!trackedRequests.has(details.requestId) && trackedRequests.size >= MAX_TRACKED_REQUESTS)
        trackedRequests.delete(trackedRequests.values().next().value);
      trackedRequests.add(details.requestId);
    } else if (!trackedRequests.has(details.requestId)) return;
    sendNative({
      event,
      kind: kind || extra.kind || 'UNKNOWN',
      url: details.url || '',
      redirectUrl: details.redirectUrl || '',
      documentUrl: details.documentUrl || '',
      originUrl: details.originUrl || '',
      requestId: details.requestId,
      frameId: Number.isFinite(details.frameId) ? details.frameId : -1,
      parentFrameId: Number.isFinite(details.parentFrameId) ? details.parentFrameId : -1,
      resourceType: String(details.type || ''),
      method: String(details.method || ''),
      statusCode: Number.isFinite(details.statusCode) ? details.statusCode : 0,
      mime: String(extra.mime || '').slice(0, 96),
      requestHeaders: extra.requestHeaders || undefined
    });
    if (event === 'MEDIA_REQUEST_COMPLETE' || event === 'MEDIA_REQUEST_ERROR')
      trackedRequests.delete(details.requestId);
  };

  const pendingHeaders = new Map();
  const MAX_PENDING_HEADERS = 128;
  const captureHeaders = rows => {
    const headers = {};
    for (const header of rows || []) {
      const name = String(header.name || '').trim();
      if (!name || name.length > 128) continue;
      const value = String(header.value || '').slice(0, 8192);
      if (value) {
        const separator = name.toLowerCase() === 'cookie' ? '; ' : ', ';
        headers[name] = headers[name] ? `${headers[name]}${separator}${value}` : value;
      }
    }
    return headers;
  };
  const onBeforeRequest = details => {
    const kind = classify(details.url, '', details.type || '');
    if (kind) requestDetails('MEDIA_REQUEST_CANDIDATE', details, {kind});
  };
  // Keep request headers briefly until the response MIME type confirms a media
  // request. Values are sent only for media candidates and are never logged or
  // persisted. This also catches manifests with opaque URLs.
  const onBeforeSendHeaders = details => {
    const requestHeaders = captureHeaders(details.requestHeaders);
    if (details.requestId) {
      if (pendingHeaders.size >= MAX_PENDING_HEADERS && !pendingHeaders.has(details.requestId))
        pendingHeaders.delete(pendingHeaders.keys().next().value);
      pendingHeaders.set(details.requestId, requestHeaders);
    }
    const kind = classify(details.url, '', details.type || '');
    if (kind) requestDetails('MEDIA_REQUEST_SENT_HEADERS', details, {kind, requestHeaders});
  };
  const onBeforeRedirect = details => requestDetails('MEDIA_REQUEST_REDIRECT', details);
  const onHeadersReceived = details => {
    const contentType = (details.responseHeaders || []).find(header =>
      String(header.name || '').toLowerCase() === 'content-type')?.value || '';
    const kind = classify(details.url, contentType, details.type || '');
    if (kind) {
      if (!trackedRequests.has(details.requestId)) {
        if (trackedRequests.size >= MAX_TRACKED_REQUESTS)
          trackedRequests.delete(trackedRequests.values().next().value);
        trackedRequests.add(details.requestId);
      }
      requestDetails('MEDIA_REQUEST_HEADERS', details, {
        kind, mime:contentType, requestHeaders:pendingHeaders.get(details.requestId) || {}
      });
    }
  };
  const onCompleted = details => {
    if (trackedRequests.has(details.requestId)) requestDetails('MEDIA_REQUEST_COMPLETE', details);
    pendingHeaders.delete(details.requestId);
  };
  const onErrorOccurred = details => {
    if (trackedRequests.has(details.requestId))
      requestDetails('MEDIA_REQUEST_ERROR', details, {kind:classify(details.url) || 'UNKNOWN'});
    pendingHeaders.delete(details.requestId);
  };

  const stopInspection = () => {
    if (!inspecting) return;
    const remove = (event, listener) => { try { event?.removeListener(listener); } catch (_) {} };
    remove(browser.webRequest?.onBeforeRequest, onBeforeRequest);
    remove(browser.webRequest?.onBeforeSendHeaders, onBeforeSendHeaders);
    remove(browser.webRequest?.onBeforeRedirect, onBeforeRedirect);
    remove(browser.webRequest?.onHeadersReceived, onHeadersReceived);
    remove(browser.webRequest?.onCompleted, onCompleted);
    remove(browser.webRequest?.onErrorOccurred, onErrorOccurred);
    inspecting = false;
    requestHeaderInspectionAvailable = false;
    trackedRequests.clear();
    pendingHeaders.clear();
    sendNative({event:'MEDIA_REQUEST_INSPECTION_STOPPED'});
  };
  const startInspection = () => {
    if (inspecting) return;
    try {
      if (!browser.webRequest) throw new Error('webRequest API unavailable');
      browser.webRequest.onBeforeRequest.addListener(onBeforeRequest,
        {urls:['http://*/*','https://*/*'], types:['xmlhttprequest','media','other','sub_frame']});
      // This is observation only: no blocking or header mutation. Keep it
      // optional so older GeckoView builds still retain URL/status inspection.
      try {
        browser.webRequest.onBeforeSendHeaders.addListener(onBeforeSendHeaders,
          {urls:['http://*/*','https://*/*'], types:['xmlhttprequest','media','other','sub_frame']}, ['requestHeaders']);
        requestHeaderInspectionAvailable = true;
      } catch (_) { requestHeaderInspectionAvailable = false; }
      browser.webRequest.onBeforeRedirect.addListener(onBeforeRedirect,
        {urls:['http://*/*','https://*/*']});
      browser.webRequest.onHeadersReceived.addListener(onHeadersReceived,
        {urls:['http://*/*','https://*/*']}, ['responseHeaders']);
      browser.webRequest.onCompleted.addListener(onCompleted,
        {urls:['http://*/*','https://*/*']});
      browser.webRequest.onErrorOccurred.addListener(onErrorOccurred,
        {urls:['http://*/*','https://*/*']});
      inspecting = true;
      sendNative({event:'MEDIA_REQUEST_INSPECTION_READY', api:true,
        requestHeaders:requestHeaderInspectionAvailable});
    } catch (error) {
      stopInspection();
      sendNative({event:'MEDIA_REQUEST_INSPECTION_UNAVAILABLE',
        reason:String(error?.message || error).slice(0,120)});
    }
  };

  const connectNative = () => {
    if (nativePort) return;
    try {
      nativePort = browser.runtime.connectNative('ja_tv_remote');
      nativePort.onMessage.addListener(message => {
        if (message?.action === 'startMediaRequestInspection') startInspection();
        else if (message?.action === 'stopMediaRequestInspection') stopInspection();
      });
      nativePort.onDisconnect.addListener(() => {
        stopInspection();
        nativePort = null;
        if (reconnectTimer === null)
          reconnectTimer = setTimeout(() => { reconnectTimer = null; connectNative(); }, 1500);
      });
    } catch (_) {
      nativePort = null;
      if (reconnectTimer === null)
        reconnectTimer = setTimeout(() => { reconnectTimer = null; connectNative(); }, 3000);
    }
  };
  connectNative();
})();
