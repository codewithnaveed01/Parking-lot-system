/* Five Star Parking — admin panel */
(() => {
  'use strict';
  const { $, el, money, fmt, dur, api, toast, tilt, countTo, renderFloors, ICONS, METHOD_ICON } = FSP;
  let token = sessionStorage.getItem('adminToken') || null;
  let tab = 'active', wdMethod = 'EASYPAISA', stats = null;
  const A = (path, opts) => api(path, opts, token);

  /* ---------- Auth ---------- */
  const showDash = (on) => { $('loginView').classList.toggle('hidden', on); $('dash').classList.toggle('hidden', !on); $('logoutBtn').classList.toggle('hidden', !on); };
  const logout = () => { if (token) A('/api/admin/logout', { method: 'POST' }).catch(() => {}); token = null; sessionStorage.removeItem('adminToken'); showDash(false); };
  $('logoutBtn').addEventListener('click', logout);
  $('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      const r = await api('/api/admin/login', { method: 'POST', body: JSON.stringify({ username: $('lUser').value, password: $('lPass').value }) });
      token = r.token; sessionStorage.setItem('adminToken', token); $('loginForm').reset(); toast('Welcome, admin'); showDash(true); boot();
    } catch (err) { toast(err.message, 'err'); }
  });
  const guard = (fn) => (...a) => Promise.resolve().then(() => fn(...a)).catch(err => { if (err && err.status === 401) { toast('Session expired', 'err'); logout(); } else toast(err && err.message ? err.message : 'Something went wrong', 'err'); });

  /* ---------- Modal ---------- */
  $('mClose').addEventListener('click', () => { $('modal').hidden = true; });
  $('modal').addEventListener('click', (e) => { if (e.target === $('modal')) $('modal').hidden = true; });
  const showWd = (w) => {
    const b = $('mBody'); b.textContent = '';
    const rh = el('div', 'rh', 'FIVE STAR PARKING'); rh.append(el('small', '', 'WITHDRAWAL CONFIRMATION')); b.append(rh);
    [['Withdrawal #', w.id], ['Method', w.methodLabel], ['Account', w.account], ['Time', fmt(w.time)], ['Reference', w.reference]].forEach(([k, v]) => { const d = el('div', 'l'); d.append(el('span', '', k), el('span', '', v)); b.append(d); });
    const d = el('div', 'l total'); d.append(el('span', '', 'AMOUNT'), el('span', '', money(w.amount))); b.append(d);
    $('modal').hidden = false;
  };

  /* ---------- Loaders ---------- */
  const loadStats = async () => {
    stats = await A('/api/admin/stats');
    $('bAvail').textContent = money(stats.availableBalance); $('bTotal').textContent = money(stats.totalRevenue); $('bWd').textContent = money(stats.withdrawn); $('b24').textContent = money(stats.revenue24h);
    countTo($('kTotal'), stats.totalSpots); countTo($('kFree'), stats.freeSpots); countTo($('kOcc'), stats.occupiedSpots); countTo($('kBlk'), stats.blockedSpots); countTo($('kTickets'), stats.totalTickets); countTo($('k24'), stats.vehiclesLast24h);
    const ch = $('chart'); ch.textContent = ''; const bm = stats.revenueByMethod || {}; const max = Math.max(1, ...Object.values(bm));
    Object.entries(bm).forEach(([k, v]) => { const c = el('div', 'bar-c'); const bar = el('i'); bar.style.height = '0px'; bar.title = money(v); c.append(el('span', '', money(v)), bar, el('span', '', k)); ch.append(c); requestAnimationFrame(() => { bar.style.height = Math.max(3, v / max * 80) + 'px'; }); });
  };
  const loadFloors = async () => renderFloors($('floors'), await A('/api/admin/floors'), guard(async (s) => {
    const st = s.status || (s.free ? 'FREE' : 'OCCUPIED');
    if (st === 'OCCUPIED') return toast(`${s.id} is occupied by ${s.plate}`, 'err');
    const block = st === 'FREE';
    if (!confirm(`${block ? 'Block' : 'Unblock'} spot ${s.id}?`)) return;
    await A('/api/admin/spot', { method: 'POST', body: JSON.stringify({ spotId: s.id, blocked: String(block) }) });
    toast(`${s.id} ${block ? 'blocked' : 'unblocked'}`); refresh();
  }));
  const loadRates = async () => {
    const rates = await api('/api/rates'); const tb = $('rateRows'); tb.textContent = '';
    rates.forEach(r => {
      const tr = el('tr'); const c1 = el('td'); c1.append(el('span', 'vi', ICONS[r.type]), document.createTextNode(r.label));
      const c2 = el('td'); const wrap = el('div', 'rate-edit'); const inp = el('input'); inp.type = 'number'; inp.min = 0; inp.step = 1; inp.value = r.rate;
      const b = el('button', 'btn small primary', 'Save'); b.type = 'button'; b.style.width = 'auto';
      b.addEventListener('click', guard(async () => { await A('/api/admin/rate', { method: 'POST', body: JSON.stringify({ type: r.type, rate: inp.value }) }); toast(`${r.label} rate updated`); }));
      wrap.append(inp, b); c2.append(wrap); tr.append(c1, c2); tb.append(tr);
    });
  };
  const renderWdMethods = () => {
    const wrap = $('wdMethods'); wrap.textContent = '';
    [['EASYPAISA', 'EasyPaisa'], ['JAZZCASH', 'JazzCash'], ['BANK', 'Bank Transfer']].forEach(([id, label]) => {
      const d = el('div', 'method' + (id === wdMethod ? ' active' : '')); d.dataset.m = id; d.append(el('span', 'mi', METHOD_ICON[id]), document.createTextNode(label));
      d.addEventListener('click', () => { wdMethod = id; renderWdMethods(); }); wrap.append(d);
    });
    $('wdMethods').style.gridTemplateColumns = 'repeat(3,1fr)';
    $('wdAcctLabel').textContent = wdMethod === 'BANK' ? 'Bank Account / IBAN' : 'Mobile Number'; $('wdAccount').placeholder = wdMethod === 'BANK' ? 'PK36SCBL0000001123456702' : '03XXXXXXXXX';
  };
  /* ---------- Tables with filters + CSV ---------- */
  let lastRows = [], lastCols = [];
  const filters = () => ({ q: ($('fSearch').value || '').trim().toLowerCase(), m: $('fMethod').value, days: Number($('fRange').value || 0) });
  const inRange = (iso, days) => !days || (iso && Date.now() - Date.parse(iso) <= days * 864e5);
  const matches = (obj, q) => !q || Object.values(obj).some(v => String(v == null ? '' : v).toLowerCase().includes(q));
  const loadTable = async () => {
    const th = $('tbl').querySelector('thead'), tb = $('tbl').querySelector('tbody'); th.textContent = ''; tb.textContent = '';
    const f = filters(); const showMethod = tab === 'history' || tab === 'payments' || tab === 'withdrawals';
    $('fMethod').style.display = showMethod ? '' : 'none';
    const head = (cols) => { lastCols = cols; const tr = el('tr'); cols.forEach(c => tr.append(el('th', '', c))); th.append(tr); };
    const empty = (n) => { const tr = el('tr'); const td = el('td', 'empty', 'No records match'); td.colSpan = n; tr.append(td); tb.append(tr); };
    const badgeType = (t) => { const td = el('td'); td.append(el('span', 'badge', `${ICONS[t] || ''} ${t}`)); return td; };
    const badgeM = (m) => { const td = el('td'); td.append(el('span', 'badge ' + (m || ''), m || '—')); return td; };
    let rows = [];

    if (tab === 'active') {
      const list = (await A('/api/admin/active')).filter(t => matches(t, f.q) && inRange(t.entryTime, f.days)); $('cActive').textContent = list.length;
      head(['Ticket', 'Plate', 'Owner', 'Type', 'Spot', 'Entry', 'Duration', '']);
      if (!list.length) empty(8);
      list.forEach(t => { const tr = el('tr'); const act = el('td'); const b = el('button', 'btn small danger', 'Force exit'); b.addEventListener('click', () => { $('fePlate').value = t.plate; $('feForm').requestSubmit(); }); act.append(b);
        tr.append(el('td', '', t.id), el('td', '', t.plate), el('td', '', t.owner), badgeType(t.vehicleType), el('td', '', t.spotId), el('td', '', fmt(t.entryTime)), el('td', '', dur(t.entryTime)), act); tb.append(tr);
        rows.push([t.id, t.plate, t.owner, t.vehicleType, t.spotId, t.entryTime, dur(t.entryTime)]); });
      $('tblSummary').textContent = `${list.length} vehicle(s) currently parked`;

    } else if (tab === 'history') {
      // Full history: every ticket ever (active + closed), newest first
      const [act, hist] = await Promise.all([A('/api/admin/active'), A('/api/admin/history')]);
      const list = [...act, ...hist].filter(t => matches(t, f.q) && (!f.m || t.paymentMethod === f.m) && inRange(t.entryTime, f.days)).sort((a, b) => b.entryTime.localeCompare(a.entryTime));
      head(['Ticket', 'Status', 'Plate', 'Owner', 'Type', 'Spot', 'Entry', 'Exit', 'Duration', 'Method', 'Fee']);
      if (!list.length) empty(11);
      let total = 0;
      list.forEach(t => { const tr = el('tr'); const st = el('td'); st.append(el('span', 'badge ' + (t.active ? 'CASH' : ''), t.active ? 'ACTIVE' : 'CLOSED'));
        const d = t.active ? dur(t.entryTime) : (() => { const m = Math.max(0, Math.round((Date.parse(t.exitTime) - Date.parse(t.entryTime)) / 60000)); return m < 60 ? `${m}m` : `${Math.floor(m / 60)}h ${m % 60}m`; })();
        tr.append(el('td', '', t.id), st, el('td', '', t.plate), el('td', '', t.owner), badgeType(t.vehicleType), el('td', '', t.spotId), el('td', '', fmt(t.entryTime)), el('td', '', fmt(t.exitTime)), el('td', '', d), badgeM(t.paymentMethod), el('td', '', t.active ? '—' : money(t.fee))); tb.append(tr);
        if (!t.active) total += t.fee; rows.push([t.id, t.active ? 'ACTIVE' : 'CLOSED', t.plate, t.owner, t.vehicleType, t.spotId, t.entryTime, t.exitTime || '', d, t.paymentMethod || '', t.active ? '' : t.fee]); });
      $('tblSummary').textContent = `${list.length} ticket(s) · ${list.filter(t => t.active).length} active · Revenue in view: ${money(total)}`;

    } else if (tab === 'payments') {
      const list = (await A('/api/admin/history')).filter(t => t.paymentRef && matches(t, f.q) && (!f.m || t.paymentMethod === f.m) && inRange(t.exitTime, f.days));
      head(['TXN Ref', 'Paid At', 'Ticket', 'Plate', 'Owner', 'Method', 'Account', 'Amount']);
      if (!list.length) empty(8);
      let total = 0;
      list.forEach(t => { const tr = el('tr'); tr.append(el('td', '', t.paymentRef), el('td', '', fmt(t.exitTime)), el('td', '', t.id), el('td', '', t.plate), el('td', '', t.owner), badgeM(t.paymentMethod), el('td', '', t.paymentAccount || '—'), el('td', '', money(t.fee))); tb.append(tr);
        total += t.fee; rows.push([t.paymentRef, t.exitTime, t.id, t.plate, t.owner, t.paymentMethod, t.paymentAccount || '', t.fee]); });
      $('tblSummary').textContent = `${list.length} payment(s) · Total: ${money(total)}`;

    } else if (tab === 'withdrawals') {
      const list = (await A('/api/admin/withdrawals')).filter(w => matches(w, f.q) && (!f.m || w.method === f.m) && inRange(w.time, f.days));
      head(['ID', 'Method', 'Account', 'Amount', 'Time', 'Reference', '']);
      if (!list.length) empty(7);
      let total = 0;
      list.forEach(w => { const tr = el('tr'); const act = el('td'); const b = el('button', 'btn small ghost', 'Receipt'); b.addEventListener('click', () => showWd(w)); act.append(b);
        tr.append(el('td', '', w.id), badgeM(w.method), el('td', '', w.account), el('td', '', money(w.amount)), el('td', '', fmt(w.time)), el('td', '', w.reference), act); tb.append(tr);
        total += w.amount; rows.push([w.id, w.method, w.account, w.amount, w.time, w.reference]); });
      $('tblSummary').textContent = `${list.length} withdrawal(s) · Total: ${money(total)}`;

    } else if (tab === 'vehicles') {
      const list = (await A('/api/admin/vehicles')).filter(v => matches(v, f.q) && inRange(v.lastSeen, f.days));
      head(['Plate', 'Owner', 'Type', 'Visits', 'Total Paid', 'First Seen', 'Last Seen', 'Status']);
      if (!list.length) empty(8);
      list.forEach(v => { const tr = el('tr'); const st = el('td'); st.append(el('span', 'badge ' + (v.active ? 'CASH' : ''), v.active ? 'PARKED' : 'AWAY'));
        tr.append(el('td', '', v.plate), el('td', '', v.owner), badgeType(v.vehicleType), el('td', '', v.visits), el('td', '', money(v.totalPaid)), el('td', '', fmt(v.firstSeen)), el('td', '', fmt(v.lastSeen)), st); tb.append(tr);
        rows.push([v.plate, v.owner, v.vehicleType, v.visits, v.totalPaid, v.firstSeen, v.lastSeen, v.active ? 'PARKED' : 'AWAY']); });
      $('tblSummary').textContent = `${list.length} registered vehicle(s)`;
    }
    lastRows = rows;
  };
  const exportCsv = () => {
    if (!lastRows.length) return toast('Nothing to export', 'err');
    const esc = (v) => { const s = String(v == null ? '' : v); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
    const csv = [lastCols.filter(c => c).join(','), ...lastRows.map(r => r.map(esc).join(','))].join('\n');
    const a = el('a'); a.href = URL.createObjectURL(new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' })); a.download = `parking-${tab}-${new Date().toISOString().slice(0, 10)}.csv`; document.body.append(a); a.click(); a.remove();
  };
  let fTimer; ['fSearch', 'fMethod', 'fRange'].forEach(id => $(id).addEventListener('input', () => { clearTimeout(fTimer); fTimer = setTimeout(() => guard(loadTable)(), 200); }));
  $('fCsv').addEventListener('click', exportCsv);
  const refresh = guard(() => Promise.all([loadStats(), loadFloors(), loadTable()]));

  /* ---------- Actions ---------- */
  $('wdMax').addEventListener('click', () => { if (stats) $('wdAmount').value = stats.availableBalance.toFixed(2); });
  $('wdForm').addEventListener('submit', (ev) => { ev.preventDefault(); guard(async () => {
    const amt = Math.round(Number($('wdAmount').value) * 100) / 100; if (!(amt > 0)) return toast('Enter a valid amount', 'err');
    if (!confirm(`Withdraw ${money(amt)} via ${wdMethod} to ${$('wdAccount').value}?`)) return;
    const w = await A('/api/admin/withdraw', { method: 'POST', body: JSON.stringify({ method: wdMethod, account: $('wdAccount').value, amount: String(amt) }) });
    toast(`Withdrawn ${money(w.amount)} · Ref ${w.reference}`); $('wdForm').reset(); renderWdMethods(); tab = 'withdrawals'; setTab(); showWd(w); refresh();
  })(); });
  $('feForm').addEventListener('submit', (ev) => { ev.preventDefault(); guard(async () => { const plate = $('fePlate').value.trim();
    if (!confirm(`Force exit ${plate.toUpperCase()}? Fee will be recorded as cash.`)) return;
    const t = await A('/api/admin/force-exit', { method: 'POST', body: JSON.stringify({ plate }) });
    toast(`Exited ${t.plate} — ${money(t.fee)}`); $('feForm').reset(); refresh();
  })(); });
  const setTab = () => document.querySelectorAll('.tab').forEach(x => x.classList.toggle('active', x.dataset.tab === tab));
  document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => { tab = b.dataset.tab; setTab(); guard(loadTable)(); }));

  /* ---------- Boot ---------- */
  let timer;
  function boot() { renderWdMethods(); guard(loadRates)(); refresh(); tilt(); clearInterval(timer); timer = setInterval(refresh, 10000); }
  if (token) A('/api/admin/session').then(() => { showDash(true); boot(); }).catch(() => logout());
  else showDash(false);
})();
