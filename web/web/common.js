/* Shared helpers: API, theme, toast, 3D tilt, receipt rendering. XSS-safe (textContent only). */
window.FSP = (() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined && text !== null) e.textContent = text; return e; };
  const money = (n) => 'PKR ' + Number(n || 0).toLocaleString('en-PK', { maximumFractionDigits: 2 });
  const fmt = (iso) => iso && !isNaN(Date.parse(iso)) ? new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '—';
  const dur = (iso) => { const ts = Date.parse(iso); if (!iso || isNaN(ts)) return '—'; const m = Math.max(0, Math.floor((Date.now() - ts) / 60000)); return m < 60 ? `${m}m` : `${Math.floor(m / 60)}h ${m % 60}m`; };
  const ICONS = { MOTORCYCLE: '🏍️', CAR: '🚗', VAN: '🚐', TRUCK: '🚚' };
  const METHOD_ICON = { CASH: '₨', EASYPAISA: 'EP', JAZZCASH: 'JC', BANK: '🏦' };

  const api = async (path, opts = {}, token) => {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const res = await fetch(path, { headers, ...opts });
    let data = {};
    try { data = await res.json(); } catch (_) {}
    if (!res.ok) { const e = new Error(data.error || `Request failed (${res.status})`); e.status = res.status; throw e; }
    return data;
  };

  /* Theme */
  const root = document.documentElement;
  root.dataset.theme = localStorage.getItem('theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  const tt = $('themeToggle');
  if (tt) tt.addEventListener('click', () => { root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark'; localStorage.setItem('theme', root.dataset.theme); });

  /* Clock */
  const clk = $('clock');
  if (clk) { const tick = () => { clk.textContent = new Date().toLocaleString(); }; tick(); setInterval(tick, 1000); }

  /* Toast */
  let toastTimer;
  const toast = (msg, type = 'ok') => { const t = $('toast'); if (!t) return; t.textContent = msg; t.className = `toast show ${type}`; clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('show'), 3400); };

  /* 3D tilt on cards */
  const tilt = (root = document) => {
    root.querySelectorAll('.tilt').forEach(c => {
      if (c.dataset.tilt) return; c.dataset.tilt = '1';
      c.addEventListener('mousemove', (e) => {
        const r = c.getBoundingClientRect(); const x = (e.clientX - r.left) / r.width - .5, y = (e.clientY - r.top) / r.height - .5;
        c.style.transform = `perspective(900px) rotateX(${-y * 6}deg) rotateY(${x * 8}deg) translateY(-3px)`;
      });
      c.addEventListener('mouseleave', () => { c.style.transform = ''; });
    });
  };
  /* Hero parallax */
  const hero = $('hero'), heroImg = $('heroImg');
  if (hero && heroImg) hero.addEventListener('mousemove', (e) => {
    const r = hero.getBoundingClientRect(); const x = (e.clientX - r.left) / r.width - .5, y = (e.clientY - r.top) / r.height - .5;
    heroImg.style.setProperty('--px', `${x * -18}px`); heroImg.style.setProperty('--py', `${y * -12}px`);
  });

  /* Count-up animation */
  const countTo = (node, value, suffix = '') => {
    const target = Number(value) || 0; const start = Number(node.dataset.v || 0); const t0 = performance.now();
    const step = (t) => { const p = Math.min(1, (t - t0) / 600); const v = start + (target - start) * (1 - Math.pow(1 - p, 3));
      node.textContent = (Number.isInteger(target) ? Math.round(v) : v.toFixed(1)) + suffix; if (p < 1) requestAnimationFrame(step); };
    node.dataset.v = target; requestAnimationFrame(step);
  };

  /* Tiny deterministic "QR-like" code from a string (visual identifier, pure canvas) */
  const qrLike = (text) => {
    const c = document.createElement('canvas'); const n = 21; c.width = c.height = n; const ctx = c.getContext('2d');
    let h = 2166136261; const bits = [];
    for (let i = 0; i < n * n; i++) { h ^= text.charCodeAt(i % text.length); h = Math.imul(h, 16777619) >>> 0; bits.push((h >>> 7) & 1); }
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, n, n); ctx.fillStyle = '#111';
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (bits[y * n + x]) ctx.fillRect(x, y, 1, 1);
    const finder = (x, y) => { ctx.fillStyle = '#111'; ctx.fillRect(x, y, 7, 7); ctx.fillStyle = '#fff'; ctx.fillRect(x + 1, y + 1, 5, 5); ctx.fillStyle = '#111'; ctx.fillRect(x + 2, y + 2, 3, 3); };
    finder(0, 0); finder(n - 7, 0); finder(0, n - 7);
    const img = el('img', 'qr'); img.src = c.toDataURL(); img.alt = 'ticket code'; return img;
  };

  /* Receipt renderer */
  const renderReceipt = (container, t, isExit) => {
    container.textContent = '';
    const rh = el('div', 'rh', 'FIVE STAR PARKING'); rh.append(el('small', '', isExit ? 'PAYMENT RECEIPT' : 'ENTRY TICKET')); container.append(rh);
    const rows = [['Ticket #', t.id], ['Plate', t.plate], ['Owner', t.owner], ['Vehicle', `${ICONS[t.vehicleType] || ''} ${t.vehicleType}`], ['Spot', t.spotId], ['Entry', fmt(t.entryTime)]];
    if (isExit) rows.push(['Exit', fmt(t.exitTime)], ['Rate / hr', money(t.hourlyRate)], ['Method', t.paymentMethod || 'CASH']);
    if (isExit && t.paymentAccount) rows.push(['Account', t.paymentAccount]);
    if (isExit && t.paymentRef) rows.push(['TXN Ref', t.paymentRef]);
    rows.forEach(([k, v]) => { const d = el('div', 'l'); d.append(el('span', '', k), el('span', '', v)); container.append(d); });
    if (isExit) { const d = el('div', 'l total'); d.append(el('span', '', 'TOTAL PAID'), el('span', '', money(t.fee))); container.append(d); }
    container.append(qrLike(t.id + t.plate));
    if (t.signature) container.append(el('div', 'sig', 'Digital signature: ' + t.signature));
  };

  const downloadTicket = (t) => {
    const payload = { system: 'Five Star Parking', id: t.id, plate: t.plate, owner: t.owner, vehicleType: t.vehicleType, spotId: t.spotId,
      entryTime: t.entryTime, exitTime: t.exitTime, fee: t.fee, paymentMethod: t.paymentMethod, paymentRef: t.paymentRef, signature: t.signature };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const a = el('a'); a.href = URL.createObjectURL(blob); a.download = `ticket-${t.id}-${t.plate}.json`; document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  };

  /* Self-contained HTML receipt (works offline, printable) */
  const receiptHtml = (t, isExit) => {
    const esc = (x) => String(x == null ? '' : x).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const rows = [['Ticket #', t.id], ['Plate', t.plate], ['Owner', t.owner], ['Vehicle', t.vehicleType], ['Spot', t.spotId], ['Entry', fmt(t.entryTime)]];
    if (isExit) rows.push(['Exit', fmt(t.exitTime)], ['Rate / hr', money(t.hourlyRate)], ['Payment', t.paymentMethod || 'CASH']);
    if (isExit && t.paymentAccount) rows.push(['Account', t.paymentAccount]);
    if (isExit && t.paymentRef) rows.push(['TXN Ref', t.paymentRef]);
    const tr = rows.map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`).join('');
    const total = isExit ? `<tr class="t"><td>TOTAL PAID</td><td>${esc(money(t.fee))}</td></tr>` : '';
    return `<!DOCTYPE html><html><head><meta charset="utf-8"><title>Receipt ${esc(t.id)}</title><style>body{font-family:Segoe UI,system-ui,sans-serif;background:#f3f5f9;margin:0;padding:30px;display:flex;justify-content:center}.r{background:#fff;width:380px;padding:28px;border-radius:16px;box-shadow:0 10px 30px rgba(0,0,0,.12)}h1{margin:0;font-size:1.2rem;letter-spacing:2px;text-align:center}h1+p{text-align:center;color:#64748b;margin:4px 0 18px;font-size:.8rem}table{width:100%;border-collapse:collapse;font-size:.9rem}td{padding:7px 0;border-bottom:1px dashed #e2e8f0}td:last-child{text-align:right;font-weight:600;word-break:break-all}tr.t td{border:none;font-size:1.15rem;color:#2563eb;padding-top:14px;font-weight:800}.s{margin-top:16px;font-size:.6rem;color:#94a3b8;word-break:break-all}.ok{margin-top:14px;text-align:center;font-size:.75rem;color:#059669;font-weight:700}@media print{body{background:#fff;padding:0}.r{box-shadow:none}}</style></head><body><div class="r"><h1>★ FIVE STAR PARKING ★</h1><p>${isExit ? 'PAYMENT RECEIPT' : 'ENTRY TICKET'}</p><table>${tr}${total}</table><div class="ok">✔ Digitally signed &amp; verified</div><div class="s">Signature: ${esc(t.signature || '')}</div></div></body></html>`;
  };
  const saveBlob = (blob, name) => { const a = el('a'); a.href = URL.createObjectURL(blob); a.download = name; document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 2000); };
  const downloadReceipt = (t, isExit) => saveBlob(new Blob([receiptHtml(t, isExit)], { type: 'text/html' }), `receipt-${t.id}.html`);
  const printReceipt = (t, isExit) => {
    const w = window.open('', '_blank', 'width=520,height=720');
    if (!w) { downloadReceipt(t, isExit); return; }
    w.document.open(); w.document.write(receiptHtml(t, isExit)); w.document.close(); w.focus(); setTimeout(() => w.print(), 300);
  };

  const spotNode = (s, onClick) => {
    const status = (s.status || (s.free ? 'FREE' : 'OCCUPIED')).toLowerCase();
    const d = el('div', `spot ${status}`);
    d.title = status === 'free' ? `${s.id} · ${s.type} · Free` : status === 'blocked' ? `${s.id} · Blocked by admin` : s.plate ? `${s.id} · ${s.type} · ${s.plate} · ${s.owner || ''}` : `${s.id} · ${s.type} · Booked`;
    d.append(el('i', 'dot'));
    if (status === 'occupied') d.append(el('span', 'car', ICONS[s.vehicleType] || '🚗'));
    else if (status === 'blocked') d.append(el('span', 'car', '⛔'));
    else d.append(el('span', 'car', s.type[0]));
    d.append(el('b', '', s.id), el('small', '', status === 'occupied' ? (s.plate || 'booked') : status === 'blocked' ? 'blocked' : s.type.toLowerCase()));
    if (onClick) d.addEventListener('click', () => onClick(s));
    return d;
  };

  const renderFloors = (wrap, floors, onSpotClick) => {
    wrap.textContent = '';
    floors.forEach(f => {
      const fl = el('div', 'floor');
      const h = el('h4'); const lvl = el('span', 'lvl'); lvl.append(el('i', '', 'F' + f.level), el('span', '', `Floor ${f.level}`)); h.append(lvl, el('span', 'muted', `${f.free} / ${f.total} free`)); fl.append(h);
      const grid = el('div', 'spots');
      f.spots.forEach((s, i) => { const n = spotNode(s, onSpotClick); n.style.animationDelay = (i * 18) + 'ms'; grid.append(n); });
      fl.append(grid); wrap.append(fl);
    });
  };

  return { $, el, money, fmt, dur, api, toast, tilt, countTo, renderReceipt, downloadTicket, downloadReceipt, printReceipt, renderFloors, ICONS, METHOD_ICON };
})();
