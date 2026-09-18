/* Five Star Parking — public UI */
(() => {
  'use strict';
  const { $, el, money, fmt, dur, api, toast, tilt, countTo, renderReceipt, downloadTicket, downloadReceipt, downloadReceiptImage, shareReceiptImage, printReceipt, renderFloors, ICONS, METHOD_ICON } = FSP;
  let methods = [], selectedMethod = 'CASH', currentTicket = null, lastReceipt = null, lastIsExit = false;

  /* ---------- Modal ---------- */
  const showReceipt = (title, t, isExit) => {
    lastReceipt = t; lastIsExit = isExit; $('mTitle').textContent = (isExit ? '🧾 ' : '🎫 ') + title;
    renderReceipt($('mBody'), t, isExit); $('modal').hidden = false;
  };
  $('mClose').addEventListener('click', () => { $('modal').hidden = true; });
  $('modal').addEventListener('click', (e) => { if (e.target === $('modal')) $('modal').hidden = true; });
  $('mDownload').addEventListener('click', () => lastReceipt && downloadTicket(lastReceipt));
  $('mReceipt').addEventListener('click', () => { if (lastReceipt) downloadReceiptImage(lastReceipt, lastIsExit).then(() => toast('Receipt image saved')); });
  $('mShare').addEventListener('click', async () => { if (!lastReceipt) return; const ok = await shareReceiptImage(lastReceipt, lastIsExit); if (!ok) { await downloadReceiptImage(lastReceipt, lastIsExit); toast('Sharing not supported here — image downloaded instead'); } });
  $('mHtml').addEventListener('click', () => lastReceipt && downloadReceipt(lastReceipt, lastIsExit));
  $('mPrint').addEventListener('click', () => lastReceipt && printReceipt(lastReceipt, lastIsExit));

  /* ---------- Loaders ---------- */
  async function loadStats() {
    const s = await api('/api/stats');
    countTo($('sTotal'), s.totalSpots); countTo($('sFree'), s.freeSpots); countTo($('sOcc'), s.occupiedSpots);
    countTo($('sPct'), s.occupancyPercent, '%'); $('sBar').style.width = s.occupancyPercent + '%';
    const b = s.activeByType || {};
    $('sMix').textContent = `${b.MOTORCYCLE || 0} / ${b.CAR || 0}`; $('sMix2').textContent = `${b.VAN || 0} / ${b.TRUCK || 0}`;
    $('lotName').textContent = s.name;
  }
  const loadFloors = async () => renderFloors($('floors'), await api('/api/floors'));
  async function loadActive() {
    const list = await api('/api/active');
    $('activeCount').textContent = list.length;
    const tb = $('activeTable').querySelector('tbody'); tb.textContent = '';
    if (!list.length) { const tr = el('tr'); const td = el('td', 'empty', 'No vehicles parked right now'); td.colSpan = 4; tr.append(td); tb.append(tr); return; }
    list.forEach(t => {
      const tr = el('tr'); const type = el('td'); type.append(el('span', 'badge', `${ICONS[t.vehicleType]} ${t.vehicleType}`));
      tr.append(el('td', '', t.spotId), type, el('td', '', fmt(t.entryTime)), el('td', '', dur(t.entryTime)));
      tb.append(tr);
    });
  }
  async function loadRates() {
    const rates = await api('/api/rates'); const sel = $('pType'); sel.textContent = '';
    const tb = $('ratesTable').querySelector('tbody'); tb.textContent = '';
    rates.forEach(r => {
      const o = el('option', '', `${ICONS[r.type]} ${r.label}`); o.value = r.type; if (r.type === 'CAR') o.selected = true; sel.append(o);
      const tr = el('tr'); const c1 = el('td'); c1.append(el('span', 'vi', ICONS[r.type]), document.createTextNode(r.label));
      tr.append(c1, el('td', '', money(r.rate) + ' / hr')); tb.append(tr);
    });
  }
  async function loadMethods() {
    methods = await api('/api/methods');
    const renderPicker = (wrap, interactive) => {
      wrap.textContent = '';
      methods.forEach(m => {
        const d = el('div', 'method' + (interactive && m.id === selectedMethod ? ' active' : '')); d.dataset.m = m.id;
        d.append(el('span', 'mi', METHOD_ICON[m.id]), document.createTextNode(m.label));
        if (interactive) d.addEventListener('click', () => { selectedMethod = m.id; renderPicker(wrap, true); updateAccountField(); });
        wrap.append(d);
      });
    };
    renderPicker($('methods'), true); renderPicker($('methodsShow'), false);
  }
  const updateAccountField = () => {
    const m = methods.find(x => x.id === selectedMethod); const needs = m && m.needsAccount && currentTicket && currentTicket.currentFee > 0;
    $('acctWrap').classList.toggle('hidden', !needs);
    if (needs) { $('acctLabel').textContent = selectedMethod === 'BANK' ? 'Bank Account / IBAN' : `${m.label} Mobile Number`; $('eAccount').placeholder = selectedMethod === 'BANK' ? 'PK36SCBL0000001123456702' : '03XXXXXXXXX'; $('eAccount').required = true; }
    else $('eAccount').required = false;
  };
  const refresh = () => Promise.all([loadStats(), loadFloors(), loadActive()]).catch(e => toast(e.message, 'err'));

  /* ---------- Entry ---------- */
  $('parkForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      const t = await api('/api/park', { method: 'POST', body: JSON.stringify({ plate: $('pPlate').value, owner: $('pOwner').value, type: $('pType').value }) });
      toast(`✅ Parked at spot ${t.spotId}`); $('parkForm').reset(); $('pType').value = 'CAR';
      showReceipt('Entry Ticket — please download', t, false); refresh();
    } catch (err) { toast(err.message, 'err'); }
  });

  /* ---------- Exit flow ---------- */
  const setStep = (n) => [1, 2, 3].forEach(i => { const s = $('st' + i); s.classList.toggle('on', i === n); s.classList.toggle('done', i < n); });
  const showFee = (t) => {
    currentTicket = t; setStep(2);
    const fp = $('feePreview'); fp.textContent = ''; fp.classList.remove('hidden');
    fp.append(el('div', '', `${ICONS[t.vehicleType]} ${t.plate} · Spot ${t.spotId} · Parked ${dur(t.entryTime)}`));
    const s = el('div'); s.append('Amount due: ', el('strong', '', money(t.currentFee))); fp.append(s);
    if (t.currentFee === 0) fp.append(el('div', 'muted small', 'Within 15‑minute grace period — no charge.'));
    $('payForm').classList.remove('hidden'); $('ePlate').value = t.plate; updateAccountField(); setStep(3);
  };
  $('feeBtn').addEventListener('click', async () => {
    const plate = $('ePlate').value.trim(), id = $('eTicket').value.trim();
    if (!plate) return toast('Enter your license plate', 'err');
    try {
      if (id) { const tk = await api(`/api/ticket?id=${encodeURIComponent(id)}&plate=${encodeURIComponent(plate)}`); if (!tk.active) return toast('This ticket is already paid/closed', 'err'); }
      showFee(await api('/api/fee?plate=' + encodeURIComponent(plate)));
    } catch (err) { toast(err.message, 'err'); }
  });
  $('eFile').addEventListener('change', async (e) => {
    const f = e.target.files[0]; const out = $('verifyResult'); out.textContent = ''; if (!f) return; e.target.value = '';
    if (f.size > 20000) return toast('File too large', 'err');
    try {
      const text = await new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result)); r.onerror = () => rej(new Error('Could not read file')); r.readAsText(f); });
      let data; try { data = JSON.parse(text); } catch (_) { throw new Error('Invalid ticket file'); }
      if (!data || typeof data !== 'object') throw new Error('Invalid ticket file');
      const r = await api('/api/verify', { method: 'POST', body: JSON.stringify({ id: String(data.id || ''), plate: String(data.plate || ''), signature: String(data.signature || '') }) });
      const badge = el('span', 'verified' + (r.valid ? '' : ' invalid'), r.valid ? '✔ Signature verified — genuine receipt' : '✖ Invalid signature — receipt was modified');
      out.append(badge);
      if (!r.valid) return;
      if (!r.active) { out.append(el('p', 'muted small', 'This ticket has already been paid. You can re-download it.')); const b = el('button', 'btn ghost small', 'View receipt'); b.type = 'button'; b.addEventListener('click', () => showReceipt('Payment Receipt', r.ticket, true)); out.append(b); return; }
      showFee(await api('/api/fee?plate=' + encodeURIComponent(r.ticket.plate)));
    } catch (err) { toast(err.message, 'err'); }
  });
  $('payForm').addEventListener('submit', async (e) => {
    e.preventDefault(); if (!currentTicket) return;
    const m = methods.find(x => x.id === selectedMethod);
    if (m && m.needsAccount && currentTicket.currentFee > 0 && !$('eAccount').value.trim()) return toast(`Enter your ${m.label} account number`, 'err');
    try {
      const t = await api('/api/exit', { method: 'POST', body: JSON.stringify({ plate: currentTicket.plate, method: selectedMethod, account: $('eAccount').value }) });
      toast(`💸 Paid ${money(t.fee)} via ${t.paymentMethod}`);
      currentTicket = null; $('payForm').classList.add('hidden'); $('feePreview').classList.add('hidden'); $('ePlate').value = ''; $('eTicket').value = ''; $('eAccount').value = ''; $('eFile').value = ''; $('verifyResult').textContent = ''; setStep(1);
      showReceipt('Payment Receipt', t, true); refresh();
    } catch (err) { toast(err.message, 'err'); }
  });
  document.querySelectorAll('[data-xtab]').forEach(b => b.addEventListener('click', () => {
    document.querySelectorAll('[data-xtab]').forEach(x => x.classList.remove('active')); b.classList.add('active');
    $('xManual').classList.toggle('hidden', b.dataset.xtab !== 'manual'); $('xUpload').classList.toggle('hidden', b.dataset.xtab !== 'upload');
  }));

  /* ---------- Init ---------- */
  tilt();
  Promise.all([loadRates(), loadMethods()]).then(refresh).catch(e => toast(e.message, 'err'));
  setInterval(refresh, 10000);
})();
