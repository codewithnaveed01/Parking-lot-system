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
  const isTouch = matchMedia('(hover: none)').matches;
  const tilt = (root = document) => {
    if (isTouch) return;
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
  /* ---- Receipt as PNG image (canvas) ---- */
  const rrect = (ctx, x, y, w, h, r) => { ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r); ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath(); };
  const wrapText = (ctx, text, maxW) => { const words = String(text).split(' '); const lines = []; let cur = ''; words.forEach(w => { const t = cur ? cur + ' ' + w : w; if (ctx.measureText(t).width > maxW && cur) { lines.push(cur); cur = w; } else cur = t; }); if (cur) lines.push(cur); return lines; };
  const receiptCanvas = (t, isExit) => {
    const W = 720, pad = 44, scale = 2;
    const rows = [['Ticket #', t.id], ['Plate', t.plate], ['Owner', t.owner], ['Vehicle', t.vehicleType], ['Spot', t.spotId], ['Entry', fmt(t.entryTime)]];
    if (isExit) rows.push(['Exit', fmt(t.exitTime)], ['Rate / hr', money(t.hourlyRate)], ['Payment', t.paymentMethod || 'CASH']);
    if (isExit && t.paymentAccount) rows.push(['Account', t.paymentAccount]);
    if (isExit && t.paymentRef) rows.push(['TXN Ref', t.paymentRef]);
    const H = 300 + rows.length * 46 + (isExit ? 90 : 0) + 200;
    const c = document.createElement('canvas'); c.width = W * scale; c.height = H * scale;
    const ctx = c.getContext('2d'); ctx.scale(scale, scale);
    ctx.fillStyle = '#eef2f7'; ctx.fillRect(0, 0, W, H);
    ctx.fillStyle = '#ffffff'; ctx.shadowColor = 'rgba(0,0,0,.15)'; ctx.shadowBlur = 24; ctx.shadowOffsetY = 8;
    rrect(ctx, 20, 20, W - 40, H - 40, 22); ctx.fill(); ctx.shadowColor = 'transparent';
    const g = ctx.createLinearGradient(0, 0, W, 0); g.addColorStop(0, '#2563eb'); g.addColorStop(1, '#7c3aed');
    ctx.save(); rrect(ctx, 20, 20, W - 40, H - 40, 22); ctx.clip(); ctx.fillStyle = g; ctx.fillRect(20, 20, W - 40, 120); ctx.restore();
    ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.font = 'bold 30px Segoe UI, Arial, sans-serif'; ctx.fillText('\u2605 FIVE STAR PARKING \u2605', W / 2, 72);
    ctx.font = '16px Segoe UI, Arial, sans-serif'; ctx.fillStyle = 'rgba(255,255,255,.9)'; ctx.fillText(isExit ? 'PAYMENT RECEIPT' : 'ENTRY TICKET', W / 2, 104);
    let y = 190; ctx.textAlign = 'left';
    rows.forEach(([k, v]) => {
      ctx.fillStyle = '#64748b'; ctx.font = '16px Segoe UI, Arial, sans-serif'; ctx.fillText(k, pad, y);
      ctx.fillStyle = '#111827'; ctx.font = 'bold 17px Segoe UI, Arial, sans-serif'; ctx.textAlign = 'right'; ctx.fillText(String(v == null ? '' : v), W - pad, y); ctx.textAlign = 'left';
      ctx.strokeStyle = '#e2e8f0'; ctx.setLineDash([4, 4]); ctx.beginPath(); ctx.moveTo(pad, y + 14); ctx.lineTo(W - pad, y + 14); ctx.stroke(); ctx.setLineDash([]);
      y += 46;
    });
    if (isExit) {
      y += 14; ctx.fillStyle = '#eff6ff'; rrect(ctx, pad - 10, y - 30, W - pad * 2 + 20, 64, 14); ctx.fill();
      ctx.fillStyle = '#1e3a8a'; ctx.font = 'bold 20px Segoe UI, Arial, sans-serif'; ctx.fillText('TOTAL PAID', pad + 6, y + 10);
      ctx.textAlign = 'right'; ctx.fillStyle = '#2563eb'; ctx.font = 'bold 30px Segoe UI, Arial, sans-serif'; ctx.fillText(money(t.fee), W - pad - 6, y + 12); ctx.textAlign = 'left';
      y += 76;
    }
    y += 16; const n = 21, cell = 5, qx = W / 2 - (n * cell) / 2; let h = 2166136261; const key = t.id + t.plate;
    ctx.fillStyle = '#111';
    for (let i = 0; i < n * n; i++) { h ^= key.charCodeAt(i % key.length); h = Math.imul(h, 16777619) >>> 0; if ((h >>> 7) & 1) ctx.fillRect(qx + (i % n) * cell, y + Math.floor(i / n) * cell, cell, cell); }
    const finder = (fx, fy) => { ctx.fillStyle = '#111'; ctx.fillRect(fx, fy, 7 * cell, 7 * cell); ctx.fillStyle = '#fff'; ctx.fillRect(fx + cell, fy + cell, 5 * cell, 5 * cell); ctx.fillStyle = '#111'; ctx.fillRect(fx + 2 * cell, fy + 2 * cell, 3 * cell, 3 * cell); };
    finder(qx, y); finder(qx + (n - 7) * cell, y); finder(qx, y + (n - 7) * cell);
    y += n * cell + 30;
    ctx.textAlign = 'center'; ctx.fillStyle = '#059669'; ctx.font = 'bold 14px Segoe UI, Arial, sans-serif'; ctx.fillText('\u2714 Digitally signed & verified', W / 2, y); y += 22;
    ctx.fillStyle = '#94a3b8'; ctx.font = '10px monospace'; wrapText(ctx, 'Signature: ' + (t.signature || ''), W - pad * 2).forEach(l => { ctx.fillText(l, W / 2, y); y += 13; });
    ctx.fillStyle = '#94a3b8'; ctx.font = '12px Segoe UI, Arial, sans-serif'; ctx.fillText('Keep this receipt. Thank you for parking with us.', W / 2, H - 40);
    return c;
  };
  const downloadReceiptImage = (t, isExit) => new Promise((resolve) => {
    const c = receiptCanvas(t, isExit); const name = `receipt-${t.id}.png`;
    if (c.toBlob) c.toBlob(b => { saveBlob(b, name); resolve(); }, 'image/png');
    else { const a = el('a'); a.href = c.toDataURL('image/png'); a.download = name; document.body.append(a); a.click(); a.remove(); resolve(); }
  });
  const shareReceiptImage = async (t, isExit) => {
    if (!navigator.share || !navigator.canShare) return false;
    const c = receiptCanvas(t, isExit);
    const blob = await new Promise(r => c.toBlob(r, 'image/png'));
    const file = new File([blob], `receipt-${t.id}.png`, { type: 'image/png' });
    if (!navigator.canShare({ files: [file] })) return false;
    try { await navigator.share({ files: [file], title: 'Parking receipt ' + t.id }); return true; } catch (_) { return false; }
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

  return { $, el, money, fmt, dur, api, toast, tilt, countTo, renderReceipt, downloadTicket, downloadReceipt, downloadReceiptImage, shareReceiptImage, printReceipt, renderFloors, ICONS, METHOD_ICON };
})();
