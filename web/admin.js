/* Manager control room — payment verification, merchant configuration and complete records. */
(() => {
  'use strict';
  const { $, el, money, fmt, methodNames, request, toast, handleError, badge, renderZoneMap, showReceipt } = Orbit;
  const TOKEN_KEY = 'orbit-admin-token'; let token = sessionStorage.getItem(TOKEN_KEY), currentTab = 'active', lastCsv = [], headers = [];
  const A = (path, payload) => request(path, payload, token);
  const view = ready => { $('adminLogin').classList.toggle('hidden', ready); $('adminDash').classList.toggle('hidden', !ready); $('logoutBtn').classList.toggle('hidden', !ready); };
  const logout = () => { if (token) A('/api/admin/logout', {}).catch(() => {}); token = null; sessionStorage.removeItem(TOKEN_KEY); view(false); };
  const fail = error => { if (error.status === 401) { logout(); toast('Admin session expired. Sign in again.', true); } else handleError(error); };
  $('logoutBtn').addEventListener('click', logout);
  $('adminLoginForm').addEventListener('submit', async event => {
    event.preventDefault(); try { const result = await request('/api/admin/login', { username: $('adminUser').value, password: $('adminPass').value });
      token = result.token; sessionStorage.setItem(TOKEN_KEY, token); $('adminLoginForm').reset(); view(true); await boot(); toast('Welcome to the Orbit Park control room.'); }
    catch (error) { fail(error); }
  });

  let detailFocus;
  const closeDetails = () => { $('detailsModal').hidden = true; if (detailFocus?.focus) detailFocus.focus(); };
  $('detailsClose').addEventListener('click', closeDetails);
  $('detailsModal').addEventListener('click', event => { if (event.target === $('detailsModal')) closeDetails(); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && !$('detailsModal').hidden) closeDetails(); });
  const showDetails = t => {
    detailFocus = document.activeElement;
    $('detailsTitle').textContent = `Ticket ${t.id}`;
    const rows = [['Booking code', t.id], ['Vehicle', `${t.vehicleType} · ${t.plate}`], ['Owner', t.owner],
      ['Dedicated bay', t.spotId], ['Source', t.channel === 'ONLINE' ? 'Online booking' : 'Gate walk-in'],
      ['Status', t.status], ['Payment status', t.paymentStatus], ['Booked', fmt(t.createdAt)],
      ['Arrival deadline', t.channel === 'ONLINE' ? fmt(t.expiresAt) : '—'],
      ['Checked in', fmt(t.entryTime)], ['Exited / verified', fmt(t.exitTime)],
      ['Locked rate', money(t.hourlyRate) + ' / hour'],
      [t.status === 'PARKED' ? 'Due now' : 'Amount charged', money(t.status === 'PARKED' ? t.currentFee : t.fee)],
      ['Method', methodNames[t.paymentMethod] || (t.status === 'CLOSED' ? 'No charge' : 'Not paid yet')], ['Sender account', t.paymentAccount || '—'],
      ['Pending reference', t.pendingRef || '—'], ['Completed reference', t.paymentRef || '—'],
      ['Meter stopped / claim submitted', fmt(t.pendingAt)], ['Operator / verifier', t.collectedBy || '—'],
      ['Cash received', t.paymentMethod === 'CASH' ? money(t.cashTendered) : '—'],
      ['Change returned', t.paymentMethod === 'CASH' ? money(t.cashChange) : '—'], ['Note / reason', t.note || '—']];
    const body = $('detailsContent'); body.replaceChildren();
    rows.forEach(([name, value]) => { const row = el('div', 'receipt-row'); row.append(el('span', '', name), el('span', '', value)); body.append(row); });
    const audit = el('pre', 'audit-events', 'Loading audit history…');
    body.append(el('h4', 'detail-audit-title', 'LIFECYCLE / PAYMENT AUDIT'), audit);
    $('detailsModal').hidden = false; $('detailsClose').focus();
    A('/api/admin/audit?id=' + encodeURIComponent(t.id)).then(data => {
      if (audit.isConnected) audit.textContent = data.events || 'No earlier audit events (legacy booking).';
    }).catch(error => { if (audit.isConnected) audit.textContent = 'Audit history could not be loaded.'; fail(error); });
  };

  const loadStats = async () => {
    const stats = await A('/api/admin/stats');
    $('aRevenue').textContent = money(stats.totalRevenue); $('aRevenue24').textContent = `Last 24h: ${money(stats.revenue24h)}`;
    $('aFree').textContent = stats.freeSpots; $('aBusy').textContent = `${stats.reservedSpots} / ${stats.occupiedSpots}`; $('aPending').textContent = stats.pendingPayments;
    $('methodTotals').replaceChildren();
    Object.entries(stats.revenueByMethod).forEach(([name, amount]) => { const card = el('div'); card.append(el('span', '', `${methodNames[name]} collected`), el('b', '', money(amount))); $('methodTotals').append(card); });
    const noCharge = el('div'); noCharge.append(el('span', '', 'No-charge exits'), el('b', '', stats.freeExits)); $('methodTotals').append(noCharge);
  };
  const loadPending = async () => {
    const tickets = (await A('/api/admin/active')).filter(t => t.paymentStatus === 'PENDING_VERIFICATION');
    $('pendingList').replaceChildren();
    if (!tickets.length) { $('pendingList').append(el('div', 'empty-state', 'All caught up. No transfer references are awaiting verification.')); return; }
    tickets.forEach(t => {
      const item = el('article', 'pending-item'), info = el('div'), sum = el('div', 'amount', money(t.pendingFee)), actions = el('div', 'pending-actions');
      info.append(el('strong', '', `${t.vehicleType} · ${t.plate} · ${t.spotId} · ${t.id}`),
        el('small', '', `${methodNames[t.paymentMethod]} · ref ${t.pendingRef} · submitted ${fmt(t.pendingAt)} · ${t.channel.toLowerCase()} booking`));
      info.append(el('small', '', `Meter paused at submission. Match ${money(t.pendingFee)}, reference AND transfer time against your actual merchant statement. Reject an unreceived or later transfer.`));
      const approve = el('button', 'btn btn-red', 'Verify & release'); approve.type = 'button';
      approve.addEventListener('click', async () => {
        if (!confirm(`Have you independently verified an ACTUAL ${methodNames[t.paymentMethod]} deposit of ${money(t.pendingFee)} with reference ${t.pendingRef} in the merchant account? Check the transfer occurred when submitted (${fmt(t.pendingAt)}), not hours later. Do not approve based on the customer's claim alone.`)) return;
        approve.disabled = true;
        try { const paid = await A('/api/admin/payment-approve', { id: t.id, verified: 'true' }); toast(`Payment verified; ${paid.spotId} released. Receipt ${paid.id} ready.`); showReceipt(paid); await refresh(); }
        catch (error) { fail(error); } finally { approve.disabled = false; }
      });
      const reject = el('button', 'btn btn-plain', 'Reject'); reject.type = 'button';
      reject.addEventListener('click', async () => { const reason = prompt('Why is this reference rejected? Customer will see this note.', 'Reference or amount not found in merchant statement'); if (reason === null) return;
        try { await A('/api/admin/payment-reject', { id: t.id, reason }); toast(`Transfer for ${t.plate} rejected. Bay remains occupied.`); await refresh(); }
        catch (error) { fail(error); }
      });
      const details = el('button', 'btn btn-plain', 'Details'); details.type = 'button'; details.addEventListener('click', () => showDetails(t));
      actions.append(details, approve, reject); item.append(info, sum, actions); $('pendingList').append(item);
    });
  };
  const loadSpots = async () => renderZoneMap($('adminZones'), await A('/api/admin/spots'), async spot => {
    const block = spot.status === 'FREE';
    if (!confirm(`${block ? 'Block' : 'Unblock'} bay ${spot.id} for maintenance?`)) return;
    try { await A('/api/admin/spot', { spotId: spot.id, blocked: String(block) }); toast(`${spot.id} ${block ? 'blocked' : 'available'}.`); await refresh(); }
    catch (error) { fail(error); }
  });
  const loadRates = async () => {
    const rates = await request('/api/rates'); $('adminRateRows').replaceChildren();
    rates.forEach(rate => {
      const row = el('div'), form = el('div'); row.append(el('span', '', rate.label));
      const field = el('input'); field.type = 'number'; field.min = '0'; field.max = '100000'; field.step = '.01'; field.value = rate.rate; field.setAttribute('aria-label', `${rate.label} rate per hour`);
      const save = el('button', 'btn btn-dark', 'Save'); save.type = 'button'; save.addEventListener('click', async () => {
        save.disabled = true; try { await A('/api/admin/rate', { type: rate.type, rate: field.value }); toast(`${rate.label} rate saved. New bookings use the new rate.`); }
        catch (error) { fail(error); } finally { save.disabled = false; }
      }); form.append(field, save); row.append(form); $('adminRateRows').append(row);
    });
  };
  const loadMerchant = async () => {
    const m = await A('/api/admin/merchant'); $('merchantName').value = m.accountName || 'Orbit Park';
    $('merchantEp').value = m.easypaisa || ''; $('merchantJc').value = m.jazzcash || '';
    $('merchantBank').value = m.bankName || 'HBL'; $('merchantIban').value = m.bankIban || '';
  };
  $('merchantForm').addEventListener('submit', async event => {
    event.preventDefault(); const button = $('merchantForm').querySelector('[type=submit]'); button.disabled = true;
    try { await A('/api/admin/merchant', { accountName: $('merchantName').value, easypaisa: $('merchantEp').value,
      jazzcash: $('merchantJc').value, bankName: $('merchantBank').value, bankIban: $('merchantIban').value });
      toast('Merchant destinations saved. Verify they belong to Orbit Park before accepting transfers.'); }
    catch (error) { fail(error); } finally { button.disabled = false; }
  });
  $('waiveForm').addEventListener('submit', async event => {
    event.preventDefault(); const plate = $('waiveQuery').value.trim(), reason = $('waiveReason').value.trim();
    if (!confirm(`Release ${plate.toUpperCase()} without collecting a payment? Reason: ${reason}`)) return;
    try { const t = await A('/api/admin/waive', { q: plate, reason }); $('waiveForm').reset(); toast(`No-charge exit for ${t.plate} recorded with audit reason.`); showReceipt(t); await refresh(); }
    catch (error) { fail(error); }
  });

  const searchText = record => Object.values(record).map(value => value == null ? '' : String(value)).join(' ').toLowerCase();
  const filters = list => {
    const q = $('adminSearch').value.trim().toLowerCase(), method = currentTab === 'active' ? '' : $('adminMethod').value, channel = $('adminChannel').value;
    return list.filter(t => (!q || searchText(t).includes(q)) && (!method || t.paymentMethod === method) && (!channel || t.channel === channel));
  };
  const recordButton = (label, handler, cls = 'btn btn-plain') => { const button = el('button', cls, label); button.type = 'button'; button.addEventListener('click', handler); return button; };
  const loadTable = async () => {
    const tab = currentTab, head = $('adminTable').querySelector('thead'), body = $('adminTable').querySelector('tbody');
    const isVehicle = tab === 'vehicles';
    $('adminMethod').classList.toggle('hidden', tab === 'active' || isVehicle);
    $('adminChannel').classList.toggle('hidden', isVehicle);
    let list;
    if (isVehicle) list = await A('/api/admin/vehicles');
    else if (tab === 'history') { const [active, history] = await Promise.all([A('/api/admin/active'), A('/api/admin/history')]); list = [...active, ...history]; }
    else if (tab === 'payments') list = (await A('/api/admin/history')).filter(t => t.paymentStatus === 'PAID');
    else list = await A('/api/admin/active');
    if (tab !== currentTab) return;
    if (tab === 'active') $('activeCount').textContent = list.length;
    list = isVehicle ? list.filter(v => !$('adminSearch').value.trim() || searchText(v).includes($('adminSearch').value.trim().toLowerCase())) : filters(list);
    if (!isVehicle) list.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    headers = tab === 'vehicles' ? ['Plate', 'Owner', 'Zone', 'Visits', 'Total paid', 'First seen', 'Last seen', 'Status'] :
      tab === 'payments' ? ['Date paid', 'Reference', 'Ticket', 'Plate', 'Method', 'Source', 'Collected', 'Verified by'] :
      tab === 'history' ? ['Ticket', 'Status', 'Plate', 'Zone', 'Bay', 'Source', 'Booked', 'Entry', 'Exit', 'Payment', 'Amount'] :
      ['Ticket', 'Plate', 'Owner', 'Zone', 'Bay', 'Source', 'Status', 'Entry', 'Due'];
    head.replaceChildren(); body.replaceChildren(); const thRow = el('tr'); (isVehicle ? headers : [...headers, 'Actions']).forEach(title => thRow.append(el('th', '', title))); head.append(thRow);
    lastCsv = [];
    if (!list.length) { const tr = el('tr'), td = el('td', '', 'No records match this view.'); td.colSpan = headers.length + (isVehicle ? 0 : 1); tr.append(td); body.append(tr); $('adminSummary').textContent = '0 records in this view'; return; }
    list.forEach(t => {
      const values = tab === 'vehicles' ? [t.plate, t.owner, t.vehicleType, t.visits, money(t.totalPaid), fmt(t.firstSeen), fmt(t.lastSeen), t.status] :
        tab === 'payments' ? [fmt(t.exitTime), t.paymentRef, t.id, t.plate, methodNames[t.paymentMethod], t.channel, money(t.fee), t.collectedBy] :
        tab === 'history' ? [t.id, t.status, t.plate, t.vehicleType, t.spotId, t.channel, fmt(t.createdAt), fmt(t.entryTime), fmt(t.exitTime), t.paymentStatus, t.status === 'CLOSED' ? money(t.fee) : '—'] :
        [t.id, t.plate, t.owner, t.vehicleType, t.spotId, t.channel, t.paymentStatus === 'PENDING_VERIFICATION' ? 'TRANSFER PENDING' : t.status, fmt(t.entryTime || t.createdAt), t.status === 'PARKED' ? money(t.currentFee) : '—'];
      const tr = el('tr'); values.forEach((value, index) => { const td = el('td');
        if ((tab === 'active' && index === 6) || (tab === 'history' && index === 1) || (tab === 'vehicles' && index === 7)) td.append(badge(value, value === 'TRANSFER PENDING' ? 'PENDING_VERIFICATION' : value));
        else td.textContent = String(value == null ? '—' : value); tr.append(td);
      });
      if (!isVehicle) {
        const buttons = el('td', 'stacked');
        buttons.append(recordButton('Details', () => showDetails(t)));
        if (t.signature) buttons.append(recordButton('Receipt', () => showReceipt(t)));
        if (t.status === 'RESERVED') buttons.append(recordButton('Cancel', async () => { if (!confirm(`Cancel unclaimed reservation ${t.id} for ${t.plate}?`)) return;
          try { await A('/api/admin/cancel', { id: t.id }); toast('Reservation cancelled and bay freed.'); await refresh(); } catch (error) { fail(error); }
        }));
        tr.append(buttons);
      }
      body.append(tr); lastCsv.push(values);
    });
    const total = tab === 'payments' ? ` · Collected ${money(list.reduce((sum, t) => sum + t.fee, 0))}` : '';
    $('adminSummary').textContent = `${list.length} record(s)${total} · Only confirmed cash / verified transfers count as revenue.`;
  };
  const safeCsv = value => { let text = String(value == null ? '' : value); if (/^[\s]*[=+@\-]/.test(text)) text = "'" + text;
    return /[",\r\n]/.test(text) ? '"' + text.replaceAll('"', '""') + '"' : text; };
  $('adminExport').addEventListener('click', () => {
    if (!lastCsv.length) return toast('No rows in this view.', true);
    const csv = [headers.map(safeCsv).join(','), ...lastCsv.map(row => row.map(safeCsv).join(','))].join('\r\n');
    const link = el('a'); link.href = URL.createObjectURL(new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' }));
    link.download = `orbit-${currentTab}-${new Date().toISOString().slice(0, 10)}.csv`; document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(link.href), 10000);
  });
  document.querySelectorAll('[data-adtab]').forEach(button => button.addEventListener('click', () => {
    currentTab = button.dataset.adtab; document.querySelectorAll('[data-adtab]').forEach(tab => { tab.classList.toggle('active', tab === button); tab.setAttribute('aria-selected', String(tab === button)); });
    loadTable().catch(fail);
  }));
  let filterTimer;
  ['adminSearch', 'adminMethod', 'adminChannel'].forEach(id => $(id).addEventListener('input', () => { clearTimeout(filterTimer); filterTimer = setTimeout(() => loadTable().catch(fail), 250); }));
  $('pendingRefresh').addEventListener('click', () => refresh().catch(fail));
  const refresh = () => Promise.all([loadStats(), loadPending(), loadSpots(), loadTable()]);
  let timer;
  const boot = async () => { await Promise.all([loadRates(), loadMerchant(), refresh()]); clearInterval(timer); timer = setInterval(() => refresh().catch(() => {}), 15000); };
  if (token) A('/api/admin/session').then(() => { view(true); boot().catch(fail); }).catch(() => logout()); else view(false);
})();
