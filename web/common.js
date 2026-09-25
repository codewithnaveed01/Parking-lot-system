/* Salim Habib Parking — shared, XSS-safe UI, network and signed receipt helpers. */
window.Orbit = (() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const el = (tag, className = '', text) => { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined && text !== null) node.textContent = String(text); return node; };
  const money = value => 'PKR ' + Number(value || 0).toLocaleString('en-PK', { maximumFractionDigits: 2 });
  const fmt = iso => iso && !Number.isNaN(Date.parse(iso)) ? new Date(iso).toLocaleString('en-PK', { dateStyle: 'medium', timeStyle: 'short' }) : '—';
  const duration = iso => { if (!iso) return '—'; const mins = Math.max(0, Math.floor((Date.now() - Date.parse(iso)) / 60000)); return mins < 60 ? mins + 'm' : `${Math.floor(mins / 60)}h ${mins % 60}m`; };
  const labels = { CAR: 'Cars', MOTORCYCLE: 'Bikes', VAN: 'Vans', TRUCK: 'Trucks' };
  const zoneIcon = (type, cls = '') => { const image = el('img', cls); image.src = `img/${type === 'MOTORCYCLE' ? 'bike' : type.toLowerCase()}.svg`; image.alt = ''; return image; };
  const methodNames = { EASYPAISA: 'easypaisa', JAZZCASH: 'JazzCash', BANK: 'Bank transfer', CASH: 'Cash' };
  const methodLogo = m => m.id === 'EASYPAISA' ? 'img/easypaisa.png' : m.id === 'JAZZCASH' ? 'img/jazzcash.png' : m.bankName === 'MEEZAN' ? 'img/meezan.webp' : 'img/hbl.png';
  const request = async (path, payload, token) => {
    const headers = { Accept: 'application/json' }; if (token) headers.Authorization = 'Bearer ' + token;
    const options = { headers, cache: 'no-store' };
    if (payload !== undefined) { options.method = 'POST'; headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(payload); }
    const res = await fetch(path, options);
    let body; try { body = await res.json(); } catch (_) { body = {}; }
    if (!res.ok) { const error = new Error(body.error || 'Request failed. Please try again.'); error.status = res.status; throw error; }
    return body;
  };
  let toastTimer;
  const toast = (text, error = false) => { const node = $('toast'); if (!node) return; node.textContent = text; node.className = 'toast show' + (error ? ' error' : ''); clearTimeout(toastTimer); toastTimer = setTimeout(() => { node.className = 'toast'; }, 4800); };
  const handleError = error => toast(error && error.message ? error.message : 'Please try again.', true);

  try { document.documentElement.dataset.theme = localStorage.getItem('orbit-theme') === 'dark' ? 'dark' : 'light'; } catch (_) {}
  if ($('themeToggle')) $('themeToggle').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next; try { localStorage.setItem('orbit-theme', next); } catch (_) {}
  });
  if ($('clock')) { const clock = () => { $('clock').textContent = new Date().toLocaleString('en-PK'); }; clock(); setInterval(clock, 1000); }
  if ($('year')) $('year').textContent = new Date().getFullYear();
  if ($('burger')) $('burger').addEventListener('click', () => {
    const open = $('siteNav').classList.toggle('open'); $('burger').setAttribute('aria-expanded', String(open));
  });
  if ($('siteNav')) $('siteNav').addEventListener('click', event => {
    if (event.target.closest('a')) { $('siteNav').classList.remove('open'); $('burger').setAttribute('aria-expanded', 'false'); }
  });

  const badge = (text, status = text) => el('span', 'status-badge ' + status, String(text).replaceAll('_', ' '));
  const detailCard = t => {
    const box = el('div', 'ticket-card'); const heading = el('h4', '', `${labels[t.vehicleType] || t.vehicleType} · ${t.plate} · ${t.spotId}`);
    box.append(heading, badge(t.paymentStatus === 'PENDING_VERIFICATION' ? 'Awaiting verification' : t.status, t.paymentStatus === 'PENDING_VERIFICATION' ? 'PENDING_VERIFICATION' : t.status));
    const grid = el('div', 'ticket-grid');
    const rows = [['Booking code', t.id], ['Source', t.channel === 'ONLINE' ? 'Online reservation' : 'Gate walk-in'],
      ['Vehicle', t.vehicleType], ['Created', fmt(t.createdAt)]];
    if (t.channel === 'ONLINE' && t.status === 'RESERVED') rows.push(['Check in before', fmt(t.expiresAt)]);
    if (t.entryTime) rows.push(['Checked in', fmt(t.entryTime)]);
    if (t.status === 'PARKED') rows.push(['Current fee', money(t.currentFee)]);
    if (t.status === 'CLOSED') rows.push(['Exit', fmt(t.exitTime)], ['Amount charged', money(t.fee)]);
    rows.forEach(([name, value]) => { const cell = el('div'); cell.append(el('span', '', name), el('strong', '', value)); grid.append(cell); });
    box.append(grid);
    if (t.note && t.paymentStatus !== 'PAID') box.append(el('div', 'notice-box', t.note));
    return box;
  };
  const renderZoneMap = (mount, zones, onSpotClick) => {
    mount.replaceChildren();
    zones.forEach(zone => {
      const wrap = el('section', 'map-zone'); const head = el('header');
      const title = el('h3'); title.append(zoneIcon(zone.type), document.createTextNode(`${labels[zone.type]} · ${zone.total} bays`));
      head.append(title, el('small', '', `${zone.free} available`)); wrap.append(head);
      const grid = el('div', 'spot-grid');
      zone.spots.forEach(spot => {
        const node = el(onSpotClick ? 'button' : 'div', 'spot ' + spot.status.toLowerCase());
        if (onSpotClick) { node.type = 'button'; node.disabled = spot.status === 'RESERVED' || spot.status === 'OCCUPIED'; node.addEventListener('click', () => onSpotClick(spot)); }
        node.title = `${spot.id}: ${spot.status.toLowerCase()}` + (spot.plate ? ` · ${spot.plate}` : '');
        node.setAttribute('aria-label', node.title);
        node.append(el('span', '', spot.id), el('small', '', spot.status === 'OCCUPIED' ? 'In use' : spot.status === 'RESERVED' ? 'Held' : spot.status === 'BLOCKED' ? 'Closed' : 'Open'));
        grid.append(node);
      });
      wrap.append(grid); mount.append(wrap);
    });
  };
  const picker = (mount, methods, onSelect, selected) => {
    mount.replaceChildren();
    methods.filter(m => m.id !== 'CASH' && m.enabled).forEach(m => {
      const button = el('button', 'method-option' + (m.id === selected ? ' active' : '')); button.type = 'button';
      button.setAttribute('aria-pressed', String(m.id === selected));
      const img = el('img'); img.src = methodLogo(m); img.alt = '';
      button.append(img, document.createTextNode(m.id === 'BANK' ? m.bankName + ' transfer' : methodNames[m.id]));
      button.addEventListener('click', () => onSelect(m)); mount.append(button);
    });
  };
  const destination = (mount, m, due) => {
    mount.replaceChildren(); mount.classList.remove('hidden');
    mount.append(el('span', '', `Send exactly ${money(due)} to ${m.id === 'BANK' ? m.bankName : methodNames[m.id]}`),
      el('b', '', m.destination), el('small', '', 'Account name: ' + m.accountName + ' · Verify the beneficiary in your own app before sending.'));
  };

  /* The JSON file is the customer's signed evidence for this specific phase. Never fake a QR code. */
  const receiptFile = t => ({ system: 'Salim Habib Parking', receiptType: t.receiptType,
    id: t.id, plate: t.plate, owner: t.owner, vehicleType: t.vehicleType, spotId: t.spotId,
    channel: t.channel, createdAt: t.createdAt, expiresAt: t.expiresAt,
    entryTime: t.entryTime, exitTime: t.exitTime, pendingAt: t.pendingAt, hourlyRate: t.hourlyRate, fee: t.fee,
    paymentMethod: t.paymentMethod, paymentAccount: t.paymentAccount, paymentRef: t.paymentRef,
    collectedBy: t.collectedBy, cashTendered: t.cashTendered, note: t.note, signature: t.signature });
  const receiptRows = t => {
    const rows = [['Booking', t.id], ['Plate', t.plate], ['Owner', t.owner], ['Vehicle', t.vehicleType],
      ['Dedicated bay', t.spotId], ['Channel', t.channel === 'ONLINE' ? 'Online booking' : 'Gate entry'], ['Booked', fmt(t.createdAt)]];
    if (t.receiptType === 'RESERVATION') rows.push(['Arrive before', fmt(t.expiresAt)]);
    if (t.entryTime && t.receiptType !== 'RESERVATION') rows.push(['Check-in', fmt(t.entryTime)], ['Rate / hour', money(t.hourlyRate)]);
    if (t.receiptType === 'PAYMENT') {
      rows.push(['Exit', fmt(t.exitTime)], ['Method', methodNames[t.paymentMethod] || 'No charge']);
      if (t.pendingAt && t.paymentMethod && t.paymentMethod !== 'CASH') rows.push(['Meter stopped', fmt(t.pendingAt)]);
      if (t.paymentAccount) rows.push(['Sender', t.paymentAccount]);
      if (t.paymentRef) rows.push(['Reference', t.paymentRef]);
      if (t.collectedBy) rows.push(['Processed by', t.collectedBy]);
      if (t.paymentMethod === 'CASH') rows.push(['Cash received', money(t.cashTendered)], ['Change returned', money(t.cashChange)]);
      if (t.note) rows.push(['Note', t.note]);
    }
    return rows;
  };
  const receiptLabel = t => t.receiptType === 'RESERVATION' ? 'RESERVATION PASS' : t.receiptType === 'ENTRY' ? 'ENTRY TICKET' : t.paymentMethod ? 'PAYMENT RECEIPT' : 'NO-CHARGE EXIT RECEIPT';
  const renderReceipt = (mount, t) => {
    mount.replaceChildren(); const head = el('div', 'receipt-head'); head.append(el('strong', '', 'SALIM HABIB PARKING'), el('small', '', receiptLabel(t))); mount.append(head);
    receiptRows(t).forEach(([label, value]) => { const row = el('div', 'receipt-row'); row.append(el('span', '', label), el('span', '', value)); mount.append(row); });
    if (t.receiptType === 'PAYMENT') { const total = el('div', 'receipt-row receipt-total'); total.append(el('span', '', t.paymentMethod ? 'TOTAL COLLECTED' : 'TOTAL DUE'), el('span', '', money(t.fee))); mount.append(total); }
    if (t.signature) mount.append(el('div', 'receipt-sign', `HMAC-SHA256 signature: ${t.signature}`));
    mount.append(el('p', 'receipt-note', 'Thank you — Salim Habib Parking'));
  };
  const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const receiptHtml = t => {
    const lines = receiptRows(t).map(([a, b]) => `<div class="row"><span>${esc(a)}</span><b>${esc(b)}</b></div>`).join('');
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Salim Habib Parking ${esc(t.id)}</title><style>body{font-family:Arial,sans-serif;background:#eee;margin:0;padding:30px;color:#1b1b1b}.paper{width:min(400px,100%);box-sizing:border-box;background:#fff;margin:auto;padding:26px;border-top:7px solid #c80d2a;box-shadow:0 12px 35px #ccc}h1{font-size:22px;letter-spacing:4px;text-align:center;margin:7px 0}h2{color:#bc1027;text-align:center;font-size:12px;letter-spacing:2px;margin-bottom:20px}.row{display:flex;justify-content:space-between;gap:15px;border-bottom:1px dashed #ddd;padding:8px 0;font-size:12px}.row b{text-align:right;overflow-wrap:anywhere}.total{color:#bb0e28;font-size:17px;font-weight:800;margin-top:20px}.sig{font-size:9px;overflow-wrap:anywhere;color:#777;margin-top:22px}@media print{body{background:#fff;padding:0}.paper{box-shadow:none}}</style></head><body><div class="paper"><h1>ORBIT PARK</h1><h2>${esc(receiptLabel(t))}</h2>${lines}${t.receiptType === 'PAYMENT' ? `<div class="row total"><span>${t.paymentMethod ? 'TOTAL COLLECTED' : 'TOTAL DUE'}</span><b>${esc(money(t.fee))}</b></div>` : ''}<div class="sig">HMAC-SHA256 signature: ${esc(t.signature)}</div></div></body></html>`;
  };
  const saveBlob = (blob, name) => {
    if (!blob) throw new Error('Could not create image. Please try HTML or print instead.');
    const link = el('a'); link.href = URL.createObjectURL(blob); link.download = name; document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(link.href), 10000);
  };
  const imageBlob = async t => {
    const rows = receiptRows(t), width = 760, scale = 2;
    // Keep long references and manager notes complete in exported PNGs.
    const linesFor = value => {
      let text = String(value ?? '—').replace(/[\r\n]+/g, ' ').trim(); const lines = [];
      while (text.length > 38) {
        const space = text.lastIndexOf(' ', 38), cut = space > 15 ? space : 38;
        lines.push(text.slice(0, cut)); text = text.slice(cut).trimStart();
      }
      if (text) lines.push(text);
      return lines.length ? lines : ['—'];
    };
    const rowHeight = value => Math.max(47, linesFor(value).length * 21 + 24);
    const height = 230 + rows.reduce((sum, [, value]) => sum + rowHeight(value), 0) + (t.receiptType === 'PAYMENT' ? 100 : 0) + 100;
    const canvas = el('canvas'); canvas.width = width * scale; canvas.height = height * scale;
    const context = canvas.getContext('2d'); if (!context) throw new Error('Canvas is not supported on this device'); context.scale(scale, scale);
    context.fillStyle = '#f1f1f1'; context.fillRect(0, 0, width, height); context.fillStyle = '#fff'; context.fillRect(22, 20, width - 44, height - 40);
    context.fillStyle = '#1b1b1b'; context.fillRect(22, 20, width - 44, 145); context.fillStyle = '#c80d2a'; context.fillRect(22, 20, 10, 145);
    context.textAlign = 'center'; context.fillStyle = '#fff'; context.font = 'bold 34px Arial, sans-serif'; context.fillText('SALIM HABIB PARKING', width / 2, 81);
    context.fillStyle = '#ff6c81'; context.font = 'bold 17px Arial, sans-serif'; context.fillText(receiptLabel(t), width / 2, 122);
    let y = 213; context.textAlign = 'left';
    rows.forEach(([key, value]) => {
      context.fillStyle = '#666'; context.font = '17px Arial, sans-serif'; context.fillText(String(key), 55, y);
      context.fillStyle = '#1b1b1b'; context.font = 'bold 17px Arial, sans-serif'; context.textAlign = 'right';
      const lines = linesFor(value);
      lines.forEach((line, index) => context.fillText(line, width - 55, y + index * 21, 430));
      context.textAlign = 'left';
      const bottom = y + rowHeight(value) - 33;
      context.strokeStyle = '#ddd'; context.setLineDash([4, 4]); context.beginPath(); context.moveTo(55, bottom); context.lineTo(width - 55, bottom); context.stroke(); context.setLineDash([]); y += rowHeight(value);
    });
    if (t.receiptType === 'PAYMENT') { context.fillStyle = '#c80d2a'; context.font = 'bold 23px Arial, sans-serif'; context.fillText(t.paymentMethod ? 'TOTAL COLLECTED' : 'TOTAL DUE', 55, y + 42); context.textAlign = 'right'; context.fillText(money(t.fee), width - 55, y + 42); context.textAlign = 'left'; y += 90; }
    context.fillStyle = '#777'; context.font = '12px Arial, sans-serif'; context.fillText('Signed ticket · Verify with your saved JSON file at Salim Habib Parking', 55, y + 30);
    context.font = '10px monospace'; context.fillText('Signature: ' + (t.signature || '').slice(0, 90), 55, y + 51);
    return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Could not create image')), 'image/png'));
  };
  let currentReceipt = null, lastFocus;
  const showReceipt = t => {
    if (!t || !t.signature || !t.receiptType) { toast('No signed receipt for this status yet.', true); return; }
    currentReceipt = t; lastFocus = document.activeElement;
    $('receiptTitle').textContent = receiptLabel(t); renderReceipt($('receiptContent'), t); $('receiptModal').hidden = false; $('receiptClose').focus();
  };
  const closeReceipt = () => { $('receiptModal').hidden = true; if (lastFocus && lastFocus.focus) lastFocus.focus(); };
  if ($('receiptModal')) {
    $('receiptClose').addEventListener('click', closeReceipt);
    $('receiptModal').addEventListener('click', e => { if (e.target === $('receiptModal')) closeReceipt(); });
    document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('receiptModal').hidden) closeReceipt(); });
    $('receiptJson').addEventListener('click', () => currentReceipt && saveBlob(new Blob([JSON.stringify(receiptFile(currentReceipt), null, 2)], { type: 'application/json' }), `orbit-ticket-${currentReceipt.id}.json`));
    $('receiptHtml').addEventListener('click', () => currentReceipt && saveBlob(new Blob([receiptHtml(currentReceipt)], { type: 'text/html' }), `orbit-receipt-${currentReceipt.id}.html`));
    $('receiptPrint').addEventListener('click', () => {
      if (!currentReceipt) return; const win = window.open('', '_blank', 'width=510,height=780');
      if (!win) { saveBlob(new Blob([receiptHtml(currentReceipt)], { type: 'text/html' }), `orbit-receipt-${currentReceipt.id}.html`); toast('Pop-up blocked; HTML receipt downloaded instead.'); return; }
      win.document.open(); win.document.write(receiptHtml(currentReceipt)); win.document.close(); win.focus(); setTimeout(() => win.print(), 350);
    });
    $('receiptPng').addEventListener('click', async () => { try { saveBlob(await imageBlob(currentReceipt), `orbit-receipt-${currentReceipt.id}.png`); toast('Receipt image saved'); } catch (err) { handleError(err); } });
    $('receiptShare').addEventListener('click', async () => {
      if (!currentReceipt) return;
      try {
        const blob = await imageBlob(currentReceipt), file = new File([blob], `orbit-receipt-${currentReceipt.id}.png`, { type: 'image/png' });
        if (navigator.canShare && navigator.canShare({ files: [file] })) await navigator.share({ files: [file], title: 'Salim Habib Parking receipt ' + currentReceipt.id });
        else { saveBlob(blob, file.name); toast('Sharing unavailable; receipt downloaded instead.'); }
      } catch (err) { if (err.name !== 'AbortError') handleError(err); }
    });
  }
  return { $, el, money, fmt, duration, zoneIcon, labels, methodNames, methodLogo, request, toast, handleError, badge, detailCard, renderZoneMap, picker, destination, receiptFile, showReceipt };
})();
