// Relay Shop UI. The app serves it as is, with no build tool. Every number comes from the server (PG and TB). Polls every second.

// Every path the UI calls is listed here. StaticPageTest checks that each path is mapped on the server.
const API = {
  getCatalog: '/api/catalog',
  getStatus: '/api/status',
  getOrders: '/api/orders?limit=30',
  getConsistency: '/api/consistency',
  getTimeline: '/api/orders/{id}/timeline',
  postCheckout: '/api/orders',
  postShip: '/api/orders/{id}/ship',
  postCancel: '/api/orders/{id}/cancel',
  getHalted: '/api/operator/halted?limit=100',
  postAbandon: '/api/operator/halted/{commandId}/abandon',
  postReconcile: '/api/operator/reconcile',
  getDemoLedger: '/api/demo/ledger',
  postDemoCut: '/api/demo/ledger/cut',
  postDemoRestore: '/api/demo/ledger/restore',
  postDemoBadPost: '/api/demo/bad-post',
  postDemoBurst: '/api/demo/burst?count=50',
};

const HALT_REASONS = {
  RESERVATION_NOT_EARLIER: 'The referenced reservation does not have an earlier sequence number than the post',
  RESERVATION_MISSING: 'The referenced reservation is not on the same ordering key',
  MALFORMED_ROW: 'The row data violates the command rules',
};

const state = {
  tab: 'store', filter: 'all', buyer: null, catalog: null, selected: null,
  orders: [], lastCompleted: null, ledgerUp: true, demo: null, linkConnected: true,
};

const $ = (id) => document.getElementById(id);
const fmt = (n) => Number(n).toLocaleString('en-US');
const baht = (satang) => '฿' + (satang / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
/** Idempotency key. crypto.randomUUID is missing on plain-http origins other than localhost. */
const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36) + '-' + Math.random().toString(36).slice(2));
const path = (template, values) => template.replace(/\{(\w+)\}/g, (_, k) => encodeURIComponent(values[k]));

/** Builds an element. Strings go in as text nodes so that server values are never parsed as HTML. */
function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

async function call(method, url, { headers, quiet } = {}) {
  const response = await fetch(url, { method, headers });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok && !quiet) throw new Error(body?.error || `${method} ${url} → ${response.status}`);
  return { status: response.status, ok: response.ok, body };
}

function toast(message, error) {
  const t = $('toast');
  t.textContent = message;
  t.className = 'toast' + (error ? ' err' : '');
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.hidden = true), 3200);
}

async function act(button, work) {
  if (button) button.disabled = true;
  try {
    await work();
  } catch (e) {
    toast(e.message, true);
  } finally {
    if (button) button.disabled = false;
  }
  refresh();
}

// ── Tabs ─────────────────────────────────────────────

function showTab(tab) {
  state.tab = tab;
  history.replaceState(null, '', '#' + tab);
  document.querySelectorAll('.nav button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
  document.querySelectorAll('main[data-view]').forEach((m) => (m.hidden = m.dataset.view !== tab));
  refresh();
}

// ── Store ────────────────────────────────────────────

function renderCatalog() {
  const { products, buyers } = state.catalog;
  state.buyer ??= buyers[0];
  $('buyers').replaceChildren(...buyers.map((b) =>
    h('button', { class: b === state.buyer ? 'on' : null, onclick: () => { state.buyer = b; renderCatalog(); } }, '#' + b)));
  $('products').replaceChildren(...products.map((p) => h('div', { class: 'prod' },
    h('div', { class: 'thumb' }, p.emoji),
    h('div', {}, h('div', { class: 'pname' }, p.name), h('div', { class: 'pmeta' }, baht(p.priceSatang) + ' · ' + p.code)),
    h('button', { class: 'buy', onclick: (e) => act(e.currentTarget, async () => { state.selected = (await postCheckout(p.code, newKey())).body.id; }) }, 'Pay'))));
}

/** Payment request. Resending with the same key returns the same order (201 → 200). */
async function postCheckout(product, key) {
  const response = await fetch(API.postCheckout, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify({ buyerId: state.buyer, product }),
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || 'Payment failed: ' + response.status);
  return { status: response.status, body };
}

function productName(code) {
  return state.catalog?.products.find((p) => p.code === code)?.name ?? code;
}

function renderOrders() {
  const list = state.filter === 'halted' ? state.orders.filter((o) => o.halted) : state.orders;
  if (!list.length) {
    $('orders').replaceChildren(h('div', { class: 'empty' },
      state.filter === 'halted' ? 'No halted orders.' : 'No orders yet. Pay for a product above.'));
    return;
  }
  $('orders').replaceChildren(...list.map((o) => h('div', {
    class: 'ord' + (o.id === state.selected ? ' sel' : ''),
    onclick: () => { state.selected = o.id; renderOrders(); refreshTimeline(); },
  },
  h('span', { class: 'id' }, '#' + o.id),
  h('span', { class: 'who' }, productName(o.product), h('small', {}, baht(o.amountSatang) + ' · buyer #' + o.buyerId)),
  h('span', { class: 'chips' },
    h('span', { class: 'chip c-' + o.chip, title: o.label + (o.failureReason ? ' — ' + o.failureReason : '') }, o.chip),
    o.halted && h('span', { class: 'chip c-halted' }, 'halted')),
  h('span', { class: 'acts' },
    o.state === 'AUTHORIZED' && h('button', { class: 'mini go', onclick: (e) => { e.stopPropagation(); act(e.currentTarget, () => call('POST', path(API.postShip, { id: o.id }))); } }, 'Ship'),
    (o.state === 'ACCEPTED' || o.state === 'AUTHORIZED') && h('button', { class: 'mini', onclick: (e) => { e.stopPropagation(); act(e.currentTarget, () => call('POST', path(API.postCancel, { id: o.id }))); } }, 'Cancel')),
  )));
}

// ── Pipeline stages and moving dots ──────────────────

function renderStatus(s) {
  state.ledgerUp = s.ledgerUp;
  $('t-accepted').textContent = fmt(s.tiles.accepted);
  $('t-completed').textContent = fmt(s.tiles.completed);
  $('t-backlog').textContent = fmt(s.tiles.backlog);
  $('t-backlog-tile').classList.toggle('bad', s.tiles.backlog > 20);
  $('t-p99').replaceChildren(s.tiles.p99Millis == null ? '—' : fmt(s.tiles.p99Millis), h('small', {}, 'ms'));
  $('t-double').textContent = s.tiles.doubleTransfers == null ? '—' : fmt(s.tiles.doubleTransfers);
  $('t-double-tile').classList.toggle('good', s.tiles.doubleTransfers === 0);
  $('t-double-tile').classList.toggle('bad', s.tiles.doubleTransfers > 0);

  $('p-pending').textContent = fmt(s.pipeline.pending);
  $('p-claimed').textContent = fmt(s.pipeline.claimed);
  $('p-applied').textContent = fmt(s.pipeline.applied);
  $('p-completed').textContent = fmt(s.pipeline.completed);
  $('p-workers-label').textContent = 'Workers ×' + s.instances.length;
  $('instances').replaceChildren(...s.instances.map((i) => h('span', {}, i.name + ' ', h('b', {}, fmt(i.submitted)), ' sent')));
  document.querySelector('.stage.s3').classList.toggle('down', !s.ledgerUp);

  $('tb-dot').className = 'dot ' + (s.ledgerUp ? 'up' : 'down');
  $('tb-label').textContent = s.ledgerUp ? 'TigerBeetle' : 'TigerBeetle — down';
  const badge = $('halted-badge');
  badge.hidden = s.pipeline.halted === 0;
  badge.textContent = s.pipeline.halted;

  const finished = s.pipeline.completed;
  if (state.lastCompleted != null) {
    const moved = Math.min(finished - state.lastCompleted, 6);
    for (let i = 0; i < moved; i++) setTimeout(() => packet(true), i * 140);
    if (!s.ledgerUp && s.pipeline.pending + s.pipeline.claimed > 0) packet(false);
  }
  state.lastCompleted = finished;
}

/** Sends one dot down the pipeline. When TB is down, a red dot stops in front of TB. */
function packet(flowing) {
  const pipe = $('pipe');
  if (getComputedStyle(pipe).display === 'grid') return;
  const width = pipe.clientWidth;
  const dot = h('div', { class: 'pkt' + (flowing ? '' : ' r') });
  dot.style.left = '112px';
  pipe.append(dot);
  const stop = flowing ? width - 8 : width * 0.58;
  dot.animate([{ left: '112px', opacity: 0 }, { opacity: 1, offset: 0.1 }, { left: stop + 'px', opacity: flowing ? 0 : 1 }],
    { duration: flowing ? 1600 : 900, easing: 'cubic-bezier(.4,0,.2,1)', fill: 'forwards' });
  setTimeout(() => dot.remove(), flowing ? 1700 : 1200);
}

// ── Timeline ─────────────────────────────────────────

async function refreshTimeline() {
  if (state.selected == null) return;
  const { body: t } = await call('GET', path(API.getTimeline, { id: state.selected }), { quiet: true });
  if (!t || !t.order) return;
  $('tl-title').textContent = `Order #${t.order.id} — ${productName(t.order.product)}`;
  const tone = t.order.chip === 'paid' || t.order.chip === 'cancelled' ? 'good' : t.order.chip === 'failed' ? 'badc' : 'hl';
  $('tl-state').replaceChildren(h('span', { class: tone }, t.order.label + (t.order.failureReason ? ' · ' + t.order.failureReason : '')));

  const start = t.steps.length ? Date.parse(t.steps[0].acceptedAt) : 0;
  const offset = (iso) => (iso ? '+' + fmt(Math.max(0, Date.parse(iso) - start)) + ' ms' : '—');
  const rows = [];
  let n = 1;
  t.steps.forEach((step, index) => {
    const accepted = { RESERVE: 'Pay → reserve accepted', POST: 'Ship → post accepted', VOID: 'Cancel → void accepted' }[step.kind] ?? step.kind;
    const where = (index === 0 ? 'same transaction as the order · ' : '') + `key order-${t.order.id} · seq ${index + 1}`;
    rows.push(h('div', { class: 'tlrow ok' }, h('span', { class: 'i' }, n++),
      h('span', { class: 'd' }, accepted, h('small', {}, where)),
      h('span', { class: 't' }, offset(step.acceptedAt))));
    const done = step.completedAt != null;
    const good = step.state === 'APPLIED';
    const word = { APPLIED: 'created', REJECTED: 'rejected', CONFLICT: 'conflict', SKIPPED: 'skipped', HALTED: 'halted', ACCEPTED: 'pending…', IN_PROGRESS: 'in flight…' }[step.state] ?? step.state;
    rows.push(h('div', { class: 'tlrow' + (done ? (good ? ' ok' : ' bad') : step.state === 'HALTED' ? ' bad' : '') },
      h('span', { class: 'i' }, n++),
      h('span', { class: 'd' }, 'TB ' + step.kind.toLowerCase() + ' ',
        h('span', { class: good ? 'hl' : done || step.state === 'HALTED' ? 'badc' : 'mute' }, word),
        h('small', {}, 'transfer ' + shortId(step.transferId) + ' · sent ×' + step.submitCount)),
      h('span', { class: 't' }, offset(step.completedAt))));
  });
  const cells = [
    [`buyer #${t.order.buyerId} held`, t.buyer ? baht(t.buyer.heldSatang) : '—'],
    [`buyer #${t.order.buyerId} paid`, t.buyer ? baht(t.buyer.paidSatang) : '—'],
    ['merchant pending', t.merchant ? baht(t.merchant.pendingSatang) : '—'],
    ['merchant posted', t.merchant ? baht(t.merchant.postedSatang) : '—'],
  ];
  $('timeline').replaceChildren(...rows,
    h('div', { class: 'ledger four', title: 'TigerBeetle balances of the whole account (not just this order)' }, ...cells.map(([k, v]) => h('div', { class: 'lc' }, h('div', { class: 'k' }, k), h('div', { class: 'v' }, v)))));
}

const shortId = (hex) => (hex.length > 12 ? hex.slice(0, 8) + '…' + hex.slice(-4) : hex);

// ── Operator ─────────────────────────────────────────

async function refreshHalted() {
  const { body: halted } = await call('GET', API.getHalted, { quiet: true });
  if (!Array.isArray(halted)) return;
  if (!halted.length) {
    $('halted').replaceChildren(h('div', { class: 'empty' }, 'No halted commands.'));
    return;
  }
  $('halted').replaceChildren(h('table', {},
    h('thead', {}, h('tr', {}, ...['command', 'ordering key · seq', 'kind', 'reason', 'halted at', ''].map((c) => h('th', {}, c)))),
    h('tbody', {}, ...halted.map((c) => h('tr', {},
      h('td', {}, c.commandId, c.orderId != null && h('small', {}, 'order #' + c.orderId)),
      h('td', {}, c.orderingKey + ' · ' + c.seq),
      h('td', {}, c.kind),
      h('td', {}, c.reason, h('small', {}, HALT_REASONS[c.reason] ?? '')),
      h('td', {}, new Date(c.haltedAt).toLocaleTimeString()),
      h('td', {}, h('button', { class: 'ghost red', onclick: (e) => act(e.currentTarget, async () => {
        const { body } = await call('POST', path(API.postAbandon, { commandId: c.commandId }));
        toast(c.commandId + ' → ' + body.outcome + (body.outcome === 'ABANDONED' ? ' · a void follows and the order is cancelled' : ''));
      }) }, 'Abandon')))))));
}

async function runReconcile(button) {
  await act(button, async () => {
    $('reconcile-result').replaceChildren(h('div', { class: 'empty' }, 'Reconciling…'));
    const { body: r } = await call('POST', API.postReconcile);
    $('reconcile-result').replaceChildren(
      h('div', { class: 'ledger' },
        h('div', { class: 'lc' }, h('div', { class: 'k' }, 'checked'), h('div', { class: 'v' }, fmt(r.checked))),
        h('div', { class: 'lc' }, h('div', { class: 'k' }, 'matched'), h('div', { class: 'v good' }, fmt(r.matched))),
        h('div', { class: 'lc' }, h('div', { class: 'k' }, 'mismatches'), h('div', { class: 'v ' + (r.mismatches.length ? 'badc' : 'good') }, fmt(r.mismatches.length)))),
      ...r.mismatches.map((m) => h('div', { class: 'tlrow bad' }, h('span', { class: 'i' }, '!'), h('span', { class: 'd' }, m.commandId, h('small', {}, m.kind)), h('span'))));
  });
}

// ── Scenarios ────────────────────────────────────────

async function refreshDemo() {
  const { status, body } = await call('GET', API.getDemoLedger, { quiet: true }).catch(() => ({ status: 0 }));
  state.demo = status === 200;
  $('demo-off').hidden = state.demo;
  document.querySelectorAll('#scenario-controls .ghost:not([data-goto]), #tb-switch').forEach((el) => {
    el.toggleAttribute('disabled', !state.demo);
    el.classList.toggle('busy', !state.demo);
  });
  if (state.demo) {
    state.linkConnected = body.connected;
    $('tb-switch').classList.toggle('off', !body.connected);
  }
}

async function doubleClick(button) {
  await act(button, async () => {
    const product = state.catalog.products[0].code;
    const key = newKey();
    const [a, b] = await Promise.all([postCheckout(product, key), postCheckout(product, key)]);
    const same = a.body.id === b.body.id;
    $('double-result').textContent = `#${a.body.id} (${a.status}) · #${b.body.id} (${b.status}) — ` + (same ? 'one order ✓' : 'two orders ✗');
    state.selected = a.body.id;
  });
}

// ── Consistency panel ────────────────────────────────

function renderConsistency(c) {
  $('c-posted').textContent = baht(c.merchantPostedSatang);
  $('c-paid').textContent = baht(c.paidOrdersSatang);
  $('c-reconciled').textContent = fmt(c.matched) + ' / ' + fmt(c.checked);
  $('c-stale').textContent = c.stale ? 'No fresh numbers from TB; showing the last values' : 'live · 1s';
  const box = $('c-verdict');
  box.className = 'eq ' + c.verdict;
  const words = {
    ok: `${baht(c.merchantPostedSatang)} = ${baht(c.paidOrdersSatang)} · mismatches 0 ✓`,
    settling: 'Writing results — totals will match shortly',
    broken: `Mismatch: mismatches ${c.mismatches} · orders charged twice ${c.doubleTransfers} ✗`,
    unknown: 'No numbers from TB yet',
  };
  box.replaceChildren(h('span', {}, 'Σ merchant posted = Σ paid orders · reconcile mismatches 0'), h('span', {}, words[c.verdict] ?? c.verdict));
}

// ── Polling ──────────────────────────────────────────

async function refresh() {
  try {
    const [status, orders, consistency] = await Promise.all([
      call('GET', API.getStatus), call('GET', API.getOrders), call('GET', API.getConsistency)]);
    $('pg-dot').className = 'dot up';
    renderStatus(status.body);
    state.orders = orders.body;
    // With no order selected, show the timeline of the most recent order
    state.selected ??= state.orders[0]?.id ?? null;
    renderOrders();
    renderConsistency(consistency.body);
    if (state.tab === 'store') await refreshTimeline();
    if (state.tab === 'operator') await refreshHalted();
    if (state.tab === 'scenarios') await refreshDemo();
  } catch (e) {
    // Keep the screen as is and only change the dot. The next poll tries again
    $('pg-dot').className = 'dot down';
  }
}

async function start() {
  document.querySelectorAll('.nav button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
  document.querySelectorAll('[data-goto]').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.goto)));
  document.querySelectorAll('[data-filter]').forEach((f) => f.addEventListener('click', () => {
    state.filter = f.dataset.filter;
    document.querySelectorAll('[data-filter]').forEach((x) => x.classList.toggle('on', x === f));
    renderOrders();
  }));
  $('reconcile').addEventListener('click', (e) => runReconcile(e.currentTarget));
  $('tb-switch').addEventListener('click', (e) => act(null, async () => {
    e.currentTarget?.classList.add('busy');
    await call('POST', state.linkConnected ? API.postDemoCut : API.postDemoRestore);
    $('tb-switch').classList.remove('busy');
    await refreshDemo();
  }));
  $('inject').addEventListener('click', (e) => act(e.currentTarget, async () => {
    const { body } = await call('POST', API.postDemoBadPost);
    state.selected = body.id;
    toast(`Injected a bad post into order #${body.id}. It halts shortly → Operator`);
  }));
  $('double').addEventListener('click', (e) => doubleClick(e.currentTarget));
  $('burst').addEventListener('click', (e) => act(e.currentTarget, async () => {
    const { body } = await call('POST', API.postDemoBurst);
    toast(`Placed ${body.orderIds.length} orders`);
  }));

  state.catalog = (await call('GET', API.getCatalog)).body;
  renderCatalog();
  // #operator or #scenarios in the URL opens that tab directly (the links in the README)
  const tab = location.hash.slice(1);
  if (['store', 'operator', 'scenarios'].includes(tab)) showTab(tab);
  await refresh();
  setInterval(refresh, 1000);
}

start();
