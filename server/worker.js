// StockAnalyzer instant alerts — a Cloudflare Worker that runs every minute.
//
// The phone registers its push token, the symbols you own / watch (no amounts, no prices paid),
// your alert settings and this week's predicted lows/highs. Every minute while a market is open
// the worker checks those prices, fresh headlines and the Quick wins scan, and pushes a
// notification through Firebase Cloud Messaging — so alerts arrive even when the app is closed.
//
// Bindings: KV namespace STATE. Secret: FCM_SERVICE_ACCOUNT (the Firebase service-account JSON).
// KV writes are kept low (free tier: 1000/day): state is only written when something changes.

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36';
const QUICKWINS = 'https://raw.githubusercontent.com/ShinobiSondre/stock-analyzer-app/quickwins/quickwins.json';
const MAX_SYMBOLS = 30;

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json' } });

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    if (req.method === 'GET' && url.pathname === '/') return json({ ok: true, service: 'stock-alerts' });
    if (req.method !== 'POST') return json({ error: 'not found' }, 404);
    let body;
    try { body = await req.json(); } catch { return json({ error: 'bad json' }, 400); }
    const id = String(body.id || '');
    const secret = String(body.secret || '');
    if (!/^[a-zA-Z0-9-]{16,64}$/.test(id) || secret.length < 16) return json({ error: 'bad id' }, 400);
    const key = 'dev:' + id;
    const old = await env.STATE.get(key, 'json');
    if (old && old.secret !== secret) return json({ error: 'forbidden' }, 403);

    if (url.pathname === '/register') {
      const dev = {
        secret,
        token: String(body.token || ''),
        symbols: (body.symbols || []).slice(0, MAX_SYMBOLS).map((s) => ({ s: String(s.s).toUpperCase(), own: !!s.own })),
        prefs: body.prefs || {},
        ranges: body.ranges || {},
      };
      if (!dev.token) return json({ error: 'no token' }, 400);
      const changed = !old || JSON.stringify({ ...old, seen: undefined }) !== JSON.stringify(dev);
      if (changed) {
        await env.STATE.put(key, JSON.stringify(dev));
        const ids = (await env.STATE.get('devices', 'json')) || [];
        if (!ids.includes(id)) await env.STATE.put('devices', JSON.stringify([...ids, id].slice(-20)));
      }
      return json({ ok: true, changed });
    }
    if (url.pathname === '/test') {
      if (!old) return json({ error: 'not registered' }, 404);
      // Sent after a few seconds so you can leave the app first (notifications show when it's closed).
      ctx.waitUntil((async () => {
        await new Promise((r) => setTimeout(r, 7000));
        await push(env, old.token, 'StockAnalyzer', 'Instant alerts work! Price moves, news and quick wins will show up like this.', {});
      })());
      return json({ ok: true });
    }
    if (url.pathname === '/unregister') {
      await env.STATE.delete(key);
      const ids = ((await env.STATE.get('devices', 'json')) || []).filter((x) => x !== id);
      await env.STATE.put('devices', JSON.stringify(ids));
      return json({ ok: true });
    }
    return json({ error: 'not found' }, 404);
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(tick(env));
  },
};

// ── Market hours ──────────────────────────────────────────────────────────
function local(tz) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour12: false, weekday: 'short', hour: '2-digit', minute: '2-digit', year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(new Date()).map((x) => [x.type, x.value]));
  return { wd: p.weekday, min: Number(p.hour) * 60 + Number(p.minute), day: `${p.year}-${p.month}-${p.day}` };
}
function marketOpen(sym) {
  const eu = sym.endsWith('.OL') || sym.endsWith('.CO') || sym.endsWith('.ST') || sym.endsWith('.DE') || sym.endsWith('.HE') || sym.endsWith('.AS') || sym.endsWith('.PA') || sym.endsWith('.L');
  const t = local(eu ? 'Europe/Oslo' : 'America/New_York');
  if (t.wd === 'Sat' || t.wd === 'Sun') return false;
  return eu ? t.min >= 9 * 60 && t.min <= 17 * 60 + 30 : t.min >= 9 * 60 + 30 && t.min <= 16 * 60;
}

// ── Yahoo ─────────────────────────────────────────────────────────────────
async function quote(sym) {
  for (const host of ['query2', 'query1']) {
    try {
      const r = await fetch(`https://${host}.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(sym)}?range=1d&interval=1d`, { headers: { 'User-Agent': UA } });
      if (!r.ok) continue;
      const m = (await r.json())?.chart?.result?.[0]?.meta;
      if (!m || !m.regularMarketPrice) continue;
      const prev = m.chartPreviousClose ?? m.previousClose;
      return { price: m.regularMarketPrice, prev, chg: prev ? (m.regularMarketPrice / prev - 1) * 100 : null, cur: m.currency || '' };
    } catch { /* try the other host */ }
  }
  return null;
}

async function headlines(sym) {
  try {
    const r = await fetch(`https://query2.finance.yahoo.com/v1/finance/search?q=${encodeURIComponent(sym)}&newsCount=6&quotesCount=0`, { headers: { 'User-Agent': UA } });
    if (!r.ok) return [];
    return ((await r.json()).news || []).map((n) => ({ title: n.title, link: n.link, publisher: n.publisher, time: n.providerPublishTime }));
  } catch { return []; }
}

// Same idea as the app's word list: clearly good or clearly bad headlines only.
const POS = ['beat', 'beats', 'surge', 'soar', 'jump', 'rally', 'record', 'upgrade', 'raises', 'raised', 'tops', 'strong', 'approval', 'approved', 'wins', 'win', 'buyback', 'outperform', 'breakthrough', 'partnership', 'deal', 'growth', 'profit', 'gain', 'gains', 'boost', 'bullish'];
const NEG = ['miss', 'misses', 'plunge', 'plunges', 'tumble', 'sink', 'sinks', 'slump', 'downgrade', 'cut', 'cuts', 'lawsuit', 'probe', 'investigation', 'recall', 'warning', 'warns', 'weak', 'loss', 'losses', 'fall', 'falls', 'drop', 'drops', 'crash', 'bearish', 'fraud', 'layoffs', 'ban', 'delay', 'halt'];
function sentiment(title) {
  const w = String(title).toLowerCase().split(/[^a-z]+/);
  let s = 0;
  for (const x of w) { if (POS.includes(x)) s++; if (NEG.includes(x)) s--; }
  return Math.sign(s);
}

// ── The every-minute check ───────────────────────────────────────────────
async function tick(env) {
  const ids = (await env.STATE.get('devices', 'json')) || [];
  if (!ids.length) return;
  const minute = Math.floor(Date.now() / 60000);
  let quick = null;
  for (const id of ids) {
    const dev = await env.STATE.get('dev:' + id, 'json');
    if (!dev || !dev.token) continue;
    const open = dev.symbols.filter((x) => marketOpen(x.s));
    if (!open.length) continue;
    const prefs = dev.prefs || {};
    const today = local('Europe/Oslo').day;
    let st = (await env.STATE.get('st:' + id, 'json')) || {};
    if (st.day !== today) st = { day: today, moved: {}, hits: [], seen: st.seen || {}, quick: [] };
    let dirty = false;
    const out = [];

    // 1) Prices: big moves and predicted lows/highs.
    const quotes = await Promise.all(open.map((x) => quote(x.s)));
    open.forEach((x, i) => {
      const q = quotes[i];
      if (!q || q.chg == null) return;
      const name = x.s.replace(/\.OL$/, '');
      const step = Number(prefs.bigMovePct) || 5;
      if (prefs.moves !== false) {
        const bucket = Math.floor(Math.abs(q.chg) / step) * Math.sign(q.chg);
        const last = st.moved[x.s] || 0;
        if (bucket !== 0 && Math.abs(bucket) > Math.abs(last) && Math.sign(bucket) === Math.sign(last || bucket)) {
          st.moved[x.s] = bucket; dirty = true;
          const up = q.chg > 0;
          out.push({ sym: x.s, title: `${name} ${up ? 'up' : 'down'} ${Math.abs(q.chg).toFixed(1)}% today`,
            body: `Now ${q.price.toFixed(2)} ${q.cur}.` + (up ? (x.own ? ' Open the app to see whether to sell before the close or keep it.' : ' Open the app to see whether it’s worth buying.')
              : (x.own ? ' Open the app to see what’s driving it.' : ' Could be a cheaper entry — open the app to check the odds.')) });
        }
      }
      const r = dev.ranges?.[x.s];
      if (r && prefs.swing !== false) {
        if (r.lo && q.price <= r.lo && !st.hits.includes(r.key + '|lo')) {
          st.hits.push(r.key + '|lo'); dirty = true;
          out.push({ sym: x.s, title: `${name} dipped to its likely low`, body: `Now ${q.price.toFixed(2)} — the app expected a dip to about ${Number(r.lo).toFixed(2)} this week` +
            (r.wlo ? ` (only 1 in 10 weeks below ${Number(r.wlo).toFixed(2)})` : '') + `. ${x.own ? 'If you were thinking of adding more' : 'If you wanted to buy'}, this is the cheaper zone it predicted.` });
        }
        if (x.own && r.hi && q.price >= r.hi && !st.hits.includes(r.key + '|hi')) {
          st.hits.push(r.key + '|hi'); dirty = true;
          out.push({ sym: x.s, title: `${name} reached its likely high`, body: `Now ${q.price.toFixed(2)} — about as high as the app expected this week (${Number(r.hi).toFixed(2)}). A realistic point to take some profit if you want to.` });
        }
      }
    });

    // 2) Fresh headlines on the stocks you own (three stocks a minute, in turn).
    if (prefs.news !== false) {
      const owned = open.filter((x) => x.own);
      const batch = owned.length <= 3 ? owned : [0, 1, 2].map((k) => owned[(minute * 3 + k) % owned.length]);
      const news = await Promise.all(batch.map((x) => headlines(x.s)));
      batch.forEach((x, i) => {
        const seen = st.seen[x.s] || [];
        const first = seen.length === 0;
        const fresh = news[i].filter((n) => n.link && !seen.includes(n.link));
        if (!fresh.length) return;
        st.seen[x.s] = [...fresh.map((n) => n.link), ...seen].slice(0, 30); dirty = true;
        if (first) return; // first look: just remember what's there
        const hot = fresh.find((n) => sentiment(n.title) !== 0 && (!n.time || Date.now() / 1000 - n.time < 3 * 3600));
        if (hot) {
          const s = sentiment(hot.title);
          out.push({ sym: x.s, title: `News on ${x.s.replace(/\.OL$/, '')}: sounds ${s > 0 ? 'good' : 'bad'}`,
            body: `“${hot.title}” (${hot.publisher || 'news'}). Prices often react within minutes — open the app to see how it’s moving.` });
        }
      });
    }

    // 3) Quick wins scan (every 5 minutes): your stocks among today's big risers.
    if (prefs.quick !== false && minute % 5 === 0) {
      if (quick === null) {
        try {
          const r = await fetch(QUICKWINS + '?t=' + minute);
          const d = r.ok ? await r.json() : null;
          quick = d && Date.now() / 1000 - d.epoch < 40 * 60 ? d.items || [] : [];
        } catch { quick = []; }
      }
      const mine = new Set(dev.symbols.map((x) => x.s));
      const hits = quick.filter((q) => mine.has(q.symbol) && !st.quick.includes(q.symbol)).slice(0, 3);
      if (hits.length) {
        st.quick.push(...hits.map((q) => q.symbol)); dirty = true;
        out.push({ sym: hits[0].symbol, tab: 'QUICK', title: '⚡ ' + hits.map((q) => `${q.symbol.replace(/\.OL$/, '')} +${q.changePct.toFixed(1)}%`).join(', '),
          body: 'Your stocks are among today’s big risers. Open Quick wins to see: sell before the close, or keep it?' });
      }
    }

    if (dirty) await env.STATE.put('st:' + id, JSON.stringify(st));
    for (const m of out.slice(0, 4)) {
      const res = await push(env, dev.token, m.title, m.body, m.tab ? { tab: m.tab } : { symbol: m.sym });
      if (res === 'gone') { await env.STATE.delete('dev:' + id); break; }
    }
  }
}

// ── Firebase Cloud Messaging (HTTP v1) ──────────────────────────────────────
function b64url(buf) {
  const bytes = typeof buf === 'string' ? new TextEncoder().encode(buf) : new Uint8Array(buf);
  let s = ''; for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function accessToken(env, sa) {
  const cached = await env.STATE.get('fcm:token', 'json');
  if (cached && cached.exp > Date.now() / 1000 + 120) return cached.token;
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claim = b64url(JSON.stringify({ iss: sa.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${head}.${claim}`));
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: `grant_type=${encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer')}&assertion=${head}.${claim}.${b64url(sig)}`,
  });
  const t = await r.json();
  if (!t.access_token) throw new Error('fcm auth failed');
  await env.STATE.put('fcm:token', JSON.stringify({ token: t.access_token, exp: now + (t.expires_in || 3600) }));
  return t.access_token;
}

async function push(env, token, title, body, data) {
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const at = await accessToken(env, sa);
  const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: 'POST',
    headers: { authorization: 'Bearer ' + at, 'content-type': 'application/json' },
    body: JSON.stringify({ message: {
      token,
      notification: { title, body },
      data: { title, body, ...data },
      android: { priority: 'high', notification: { channel_id: 'alerts', icon: 'ic_notification' } },
    } }),
  });
  if (r.status === 404 || r.status === 410) return 'gone';
  return r.ok ? 'ok' : 'error';
}
