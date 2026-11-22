const DEFAULT_PAGE = 'https://freeiptv2023-d.ottc.xyz/?action=view';
const MAX_PAGE_BYTES = 2_000_000;
const MAX_M3U_BYTES = 30_000_000;

function decodeHtml(value) {
  return String(value || '')
    .replace(/&amp;/gi, '&').replace(/&quot;/gi, '"').replace(/&#39;|&apos;/gi, "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)));
}
function attr(tag, key) {
  const doubleQuoted = new RegExp("(?:^|\\s)" + key + "\\s*=\\s*\"([^\"]*)\"", "i");
  const bare = new RegExp("(?:^|\\s)" + key + "\\s*=\\s*([^\\s>]+)", "i");
  const match = tag.match(doubleQuoted) || tag.match(bare);
  return match ? decodeHtml(match[1]) : '';
}
function pageCredential(html, kind) {
  const label = kind === 'username' ? /(?:iptv\s*)?user(?:name)?/i : /(?:iptv\s*)?pass(?:word)?/i;
  const inputs = [...html.matchAll(/<input\b[^>]*>/gi)].map(m => m[0]);
  for (const tag of inputs) {
    const clues = [attr(tag, 'name'), attr(tag, 'id'), attr(tag, 'aria-label'), attr(tag, 'placeholder'), attr(tag, 'autocomplete')].join(' ');
    if (label.test(clues)) {
      const value = attr(tag, 'value');
      if (value) return value.trim();
    }
  }
  const labelText = kind === 'username' ? 'IPTV Username' : 'IPTV Password';
  const plain = decodeHtml(html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
      .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ')
      .replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' '));
  const at = plain.toLowerCase().indexOf(labelText.toLowerCase());
  if (at >= 0) {
    const after = plain.slice(at + labelText.length, at + labelText.length + 160);
    const value = after.match(/[A-Za-z0-9._-]{4,}/);
    if (value && !/^(copy|copied|username|password)$/i.test(value[0])) return value[0];
  }
  throw new Error('Could not read the ' + kind + ' field from the source page');
}
function playlistHref(html, pageUrl) {
  const tags = [...html.matchAll(/<(?:a|form|button)\b[^>]*>/gi)].map(m => m[0]);
  for (const tag of tags) {
    const value = attr(tag, 'href') || attr(tag, 'action') || attr(tag, 'data-href') || attr(tag, 'data-url');
    const text = (attr(tag, 'aria-label') + ' ' + attr(tag, 'title') + ' ' + tag).toLowerCase();
    if (value && (/m3u|playlist|get\.php|download/i.test(value) || /m3u|playlist/i.test(text))) {
      try { return new URL(value, pageUrl).toString(); } catch (_) {}
    }
  }
  const literal = decodeHtml(html).match(/https?:[^"'\s<>]+(?:m3u|playlist|get\.php)[^"'\s<>]*/i);
  if (literal) { try { return new URL(literal[0], pageUrl).toString(); } catch (_) {} }
  return '';
}
function resolvePlaylist(template, discovered, username, password, pageUrl) {
  let raw = template || discovered;
  if (!raw) throw new Error('No M3U download link was found on the page; configure PLAYLIST_URL_TEMPLATE in Cloudflare if the page only displays credentials');
  raw = raw.replace(/\{username\}|%USERNAME%|\$username/gi, encodeURIComponent(username))
           .replace(/\{password\}|%PASSWORD%|\$password/gi, encodeURIComponent(password));
  const url = new URL(raw, pageUrl);
  if (!/^https?:$/.test(url.protocol)) throw new Error('The M3U URL must use HTTP or HTTPS');
  if (!/(?:username|user)=/i.test(url.search)) url.searchParams.set('username', username);
  if (!/(?:password|pass)=/i.test(url.search)) url.searchParams.set('password', password);
  return url.toString();
}
function countryIsUs(info) {
  const text = [info.country, info.group, info.id, info.name].join(' ').toLowerCase();
  return /(?:^|[^a-z])(us|usa|united\s+states)(?:$|[^a-z])/.test(text)
      || /\.us(?:$|[^a-z0-9])/.test(info.id.toLowerCase())
      || /^(?:us|usa|united states)\s*[:|/-]/i.test(info.name);
}
function m3uAttribute(line, key) {
  const re = new RegExp('(?:^|\\s)' + key + '="([^"]*)"', 'i');
  const match = line.match(re);
  return match ? decodeHtml(match[1]) : '';
}
function parseM3u(text) {
  const rows = [];
  let current = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTINF:')) {
      const comma = line.lastIndexOf(',');
      current = {
        id: m3uAttribute(line, 'tvg-id'),
        logo: m3uAttribute(line, 'tvg-logo'),
        group: m3uAttribute(line, 'group-title'),
        country: m3uAttribute(line, 'tvg-country'),
        name: comma >= 0 ? line.slice(comma + 1).trim() : m3uAttribute(line, 'tvg-name')
      };
    } else if (current && !line.startsWith('#')) {
      rows.push({ ...current, url: line });
      current = null;
    }
  }
  return rows;
}
async function probe(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 7000);
  try {
    const response = await fetch(url, { method: 'GET', redirect: 'follow', signal: controller.signal,
      headers: { 'User-Agent': 'JA-Live-TV-Refresh/1.0', 'Range': 'bytes=0-0' } });
    const ok = response.status >= 200 && response.status < 300;
    try { await response.body?.cancel(); } catch (_) {}
    return ok;
  } catch (_) { return false; }
  finally { clearTimeout(timer); }
}
async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i], i);
    }
  }));
  return out;
}
function utf8Base64(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, Math.min(i + 0x8000, bytes.length)));
  }
  return btoa(binary);
}
async function github(env, path, init) {
  const response = await fetch('https://api.github.com' + path, {
    ...init,
    headers: { 'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + env.GITHUB_TOKEN,
      'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'JA-Live-TV-Refresh',
      ...(init && init.headers || {}) }
  });
  const body = await response.text();
  if (!response.ok) throw new Error('GitHub API returned HTTP ' + response.status);
  return body ? JSON.parse(body) : {};
}
async function ensureDataBranch(env, repository, branch) {
  try { await github(env, '/repos/' + repository + '/branches/' + encodeURIComponent(branch)); return; }
  catch (_) {}
  const repo = await github(env, '/repos/' + repository);
  const base = await github(env, '/repos/' + repository + '/branches/' + encodeURIComponent(repo.default_branch));
  try {
    await github(env, '/repos/' + repository + '/git/refs', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ref: 'refs/heads/' + branch, sha: base.commit.sha })
    });
  } catch (_) {
    await github(env, '/repos/' + repository + '/branches/' + encodeURIComponent(branch));
  }
}
async function publishCatalog(env, catalog) {
  const repository = env.GITHUB_REPOSITORY || 'jdaraver2011/ja_app';
  const branch = env.DATA_BRANCH || 'live-tv-data';
  await ensureDataBranch(env, repository, branch);
  let current = null;
  try {
    current = await github(env, '/repos/' + repository + '/contents/catalog.json?ref=' + encodeURIComponent(branch));
  } catch (_) {}
  const payload = { message: 'Refresh verified U.S. test IPTV catalog', branch,
    content: utf8Base64(JSON.stringify(catalog, null, 2)) };
  if (current && current.sha) payload.sha = current.sha;
  await github(env, '/repos/' + repository + '/contents/catalog.json', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  });
}
async function refresh(env) {
  if (!env.GITHUB_TOKEN) throw new Error('Cloudflare secret GITHUB_TOKEN is not configured');
  const pageUrl = env.SOURCE_PAGE_URL || DEFAULT_PAGE;
  const pageResponse = await fetch(pageUrl, { redirect: 'follow', headers: { 'User-Agent': 'JA-Live-TV-Refresh/1.0' } });
  if (!pageResponse.ok) throw new Error('Source page returned HTTP ' + pageResponse.status);
  const pageLength = Number(pageResponse.headers.get('content-length') || 0);
  if (pageLength > MAX_PAGE_BYTES) throw new Error('Source page exceeded the size limit');
  const html = await pageResponse.text();
  if (html.length > MAX_PAGE_BYTES) throw new Error('Source page exceeded the size limit');
  const username = pageCredential(html, 'username');
  const password = pageCredential(html, 'password');
  const playlistUrl = resolvePlaylist(env.PLAYLIST_URL_TEMPLATE, playlistHref(html, pageUrl), username, password, pageUrl);
  const response = await fetch(playlistUrl, { redirect: 'follow', headers: { 'User-Agent': 'JA-Live-TV-Refresh/1.0' } });
  if (!response.ok) throw new Error('M3U download returned HTTP ' + response.status);
  const length = Number(response.headers.get('content-length') || 0);
  if (length > MAX_M3U_BYTES) throw new Error('M3U exceeded the size limit');
  const m3u = await response.text();
  if (m3u.length > MAX_M3U_BYTES || !m3u.trimStart().startsWith('#EXTM3U')) throw new Error('M3U response was invalid or exceeded the size limit');
  const allUs = parseM3u(m3u).filter(row => row.url && countryIsUs(row));
  const max = Number(env.MAX_CHANNELS || 2000);
  if (allUs.length > max) throw new Error('U.S. channel count exceeded MAX_CHANNELS; refusing to publish a partial catalog');
  const checked = await mapLimit(allUs, 12, async row => ({ row, working: await probe(row.url) }));
  const seen = new Map();
  const channels = [];
  for (const item of checked) {
    if (!item.working) continue;
    const row = item.row;
    const base = (row.id || row.name || 'channel').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'channel';
    const n = (seen.get(base) || 0) + 1;
    seen.set(base, n);
    channels.push({ id: 'test-' + base + (n > 1 ? '-' + n : ''), name: row.name || row.id || 'U.S. channel',
      logo: /^https?:\/\//i.test(row.logo) ? row.logo : '', categories: ['test'], streams: [{ url: row.url }] });
  }
  if (!channels.length) throw new Error('No working U.S. channels were found; existing catalog was left untouched');
  const catalog = { schema: 1, generatedAt: new Date().toISOString(), channels,
    counts: { candidates: allUs.length, working: channels.length, categories: { test: channels.length } } };
  await publishCatalog(env, catalog);
  return { generatedAt: catalog.generatedAt, candidates: allUs.length, working: channels.length };
}
export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(refresh(env).catch(error => console.error('Live TV refresh failed:', error.message)));
  },
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health') return Response.json({ service: 'ja-live-tv-refresh', ok: true });
    if (url.pathname === '/refresh' && request.method === 'POST') {
      const token = env.ADMIN_TOKEN;
      if (!token || request.headers.get('Authorization') !== 'Bearer ' + token) return new Response('Unauthorized', { status: 401 });
      try { return Response.json({ ok: true, ...(await refresh(env)) }); }
      catch (error) { console.error('Live TV refresh failed:', error.message); return Response.json({ ok: false, error: error.message }, { status: 502 }); }
    }
    return new Response('Not found', { status: 404 });
  }
};
