// Opt-in, read-only media request diagnostics for an active IPTV test session.
// Full request URLs remain transient in memory and are never written to logs.
(() => {
  'use strict';
  let nativePort = null;
  let reconnectTimer = null;
  let inspecting = false;
  const trackedRequests = new Set();
  const MAX_TRACKED_REQUESTS = 96;

  const classify = (raw, mime = '') => {
    let url;
    try { url = new URL(raw); } catch (_) { return null; }
    if (!['http:', 'https:'].includes(url.protocol)) return null;
    const path = url.pathname.toLowerCase();
    const type = String(mime || '').toLowerCase();
    if (/\.m3u8?$/.test(path) || /mpegurl|vnd\.apple\.mpegurl/.test(type) ||
        /(?:^|[?&])(?:format|type)=m3u8(?:&|$)/i.test(url.search)) return 'HLS';
    if (/\.mpd$/.test(path) || /dash\+xml/.test(type) ||
        /(?:^|[?&])(?:format|type)=mpd(?:&|$)/i.test(url.search)) return 'DASH';
    return null;
  };
  const sendNative = message => {
    try { nativePort?.postMessage(message); } catch (_) {}
  };
  const requestDetails = (event, details, extra = {}) => {
    if (!details || !details.requestId) return;
    const kind = classify(details.url, extra.mime || '');
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
      mime: String(extra.mime || '').slice(0, 96)
    });
    if (event === 'MEDIA_REQUEST_COMPLETE' || event === 'MEDIA_REQUEST_ERROR')
      trackedRequests.delete(details.requestId);
  };

  const onBeforeRequest = details => {
    const kind = classify(details.url);
    if (kind) requestDetails('MEDIA_REQUEST_CANDIDATE', details, {kind});
  };
  const onBeforeRedirect = details => requestDetails('MEDIA_REQUEST_REDIRECT', details);
  const onHeadersReceived = details => {
    const contentType = (details.responseHeaders || []).find(header =>
      String(header.name || '').toLowerCase() === 'content-type')?.value || '';
    if (classify(details.url, contentType))
      requestDetails('MEDIA_REQUEST_HEADERS', details, {mime:contentType});
  };
  const onCompleted = details => {
    if (trackedRequests.has(details.requestId)) requestDetails('MEDIA_REQUEST_COMPLETE', details);
  };
  const onErrorOccurred = details => {
    if (trackedRequests.has(details.requestId))
      requestDetails('MEDIA_REQUEST_ERROR', details, {kind:classify(details.url) || 'UNKNOWN'});
  };

  const stopInspection = () => {
    if (!inspecting) return;
    try {
      browser.webRequest.onBeforeRequest.removeListener(onBeforeRequest);
      browser.webRequest.onBeforeRedirect.removeListener(onBeforeRedirect);
      browser.webRequest.onHeadersReceived.removeListener(onHeadersReceived);
      browser.webRequest.onCompleted.removeListener(onCompleted);
      browser.webRequest.onErrorOccurred.removeListener(onErrorOccurred);
    } catch (_) {}
    inspecting = false;
    trackedRequests.clear();
    sendNative({event:'MEDIA_REQUEST_INSPECTION_STOPPED'});
  };
  const startInspection = () => {
    if (inspecting) return;
    try {
      if (!browser.webRequest) throw new Error('webRequest API unavailable');
      browser.webRequest.onBeforeRequest.addListener(onBeforeRequest,
        {urls:['http://*/*','https://*/*'], types:['xmlhttprequest','media','other']});
      browser.webRequest.onBeforeRedirect.addListener(onBeforeRedirect,
        {urls:['http://*/*','https://*/*']});
      browser.webRequest.onHeadersReceived.addListener(onHeadersReceived,
        {urls:['http://*/*','https://*/*']}, ['responseHeaders']);
      browser.webRequest.onCompleted.addListener(onCompleted,
        {urls:['http://*/*','https://*/*']});
      browser.webRequest.onErrorOccurred.addListener(onErrorOccurred,
        {urls:['http://*/*','https://*/*']});
      inspecting = true;
      sendNative({event:'MEDIA_REQUEST_INSPECTION_READY', api:true});
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
