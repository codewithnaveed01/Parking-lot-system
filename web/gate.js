/* Gate tablet: the only place where on-site cash can be recorded. */
(() => {
  'use strict';
  const { $, el, money, fmt, labels, request, toast, handleError, badge, detailCard, picker, destination, showReceipt } = Orbit;
  const TOKEN_KEY = 'orbit-gate-token'; let token = sessionStorage.getItem(TOKEN_KEY); let methods = [], selected = null, current = null, tab = 'walkin';
  const G = (path, body) => request(path, body, token);
  const view = ready => { $('gateLogin').classList.toggle('hidden', ready); $('gateDash').classList.toggle('hidden', !ready); $('logoutBtn').classList.toggle('hidden', !ready); };
  const logout = () => { if (token) G('/api/guard/logout', {}).catch(() => {}); token = null; sessionStorage.removeItem(TOKEN_KEY); view(false); current = null; };
  const fail = error => { if (error.status === 401) { logout(); toast('Gate session expired. Sign in again.', true); } else handleError(error); };
  $('logoutBtn').addEventListener('click', logout);
  $('gateLoginForm').addEventListener('submit', async event => {
    event.preventDefault(); try { const result = await request('/api/guard/login', { username: $('gateUser').value, password: $('gatePass').value });
      token = result.token; sessionStorage.setItem(TOKEN_KEY, token); $('gateLoginForm').reset(); view(true); await boot(); toast('Gate desk ready.'); }
    catch (error) { fail(error); }
  });
  const setTab = value => { tab = value;
    document.querySelectorAll('[data-gate-tab]').forEach(node => { const active = node.dataset.gateTab === value; node.classList.toggle('active', active); node.setAttribute('aria-selected', String(active)); });
    ['walkin', 'arrival', 'exit'].forEach(name => $('tab-' + name).classList.toggle('hidden', name !== value));
  };
  document.querySelectorAll('[data-gate-tab]').forEach(node => node.addEventListener('click', () => setTab(node.dataset.gateTab)));
  const stats = async () => {
    const data = await request('/api/stats'); $('gateFree').textContent = data.freeSpots; $('gateReserved').textContent = data.reservedSpots; $('gateOccupied').textContent = data.occupiedSpots;
    const mount = $('gateZoneCounts'); mount.replaceChildren();
    ['CAR', 'MOTORCYCLE', 'VAN', 'TRUCK'].forEach(type => { const row = el('div'); row.append(el('span', '', labels[type]), el('strong', '', data.zones[type].free + ' / ' + data.zones[type].total + ' free')); mount.append(row); });
  };
  const loadQueue = async () => {
    const list = await G('/api/guard/active'), body = $('gateActiveTable').querySelector('tbody'); body.replaceChildren();
    if (!list.length) { const tr = el('tr'), td = el('td', '', 'No vehicles on site or awaiting arrival.'); td.colSpan = 7; tr.append(td); body.append(tr); return; }
    list.forEach(t => {
      const tr = el('tr'), actions = el('td'), button = el('button', 'btn btn-plain', t.status === 'RESERVED' ? 'Check in' : 'Open exit'); button.type = 'button';
      button.addEventListener('click', () => {
        if (t.status === 'RESERVED') { $('cId').value = t.id; $('cPlate').value = t.plate; setTab('arrival'); $('cId').focus(); }
        else { $('gSearch').value = t.plate; setTab('exit'); lookup(); }
        window.scrollTo({ top: 100, behavior: 'smooth' });
      }); actions.append(button);
      tr.append(el('td', '', t.id), el('td', '', t.plate), el('td', '', labels[t.vehicleType]),
        el('td', '', t.spotId), (() => { const cell = el('td'); cell.append(badge(t.paymentStatus === 'PENDING_VERIFICATION' ? 'Transfer pending' : t.status, t.paymentStatus === 'PENDING_VERIFICATION' ? 'PENDING_VERIFICATION' : t.status)); return cell; })(),
        el('td', '', fmt(t.entryTime || t.createdAt)), actions); body.append(tr);
    });
  };
  const refresh = async () => Promise.all([stats(), loadQueue()]);
  $('gateRefresh').addEventListener('click', () => refresh().catch(fail));

  $('walkinForm').addEventListener('submit', async event => {
    event.preventDefault(); const button = $('walkinForm').querySelector('[type=submit]'); button.disabled = true;
    try { const t = await G('/api/guard/park', { plate: $('gPlate').value, owner: $('gOwner').value, type: $('gType').value });
      $('walkinForm').reset(); showReceipt(t); toast(`${t.plate} checked in at ${t.spotId}. Save or print the entry ticket.`); await refresh(); }
    catch (error) { fail(error); } finally { button.disabled = false; }
  });
  $('checkinForm').addEventListener('submit', async event => {
    event.preventDefault(); const button = $('checkinForm').querySelector('[type=submit]'); button.disabled = true;
    try { const t = await G('/api/guard/checkin', { id: $('cId').value, plate: $('cPlate').value });
      $('checkinForm').reset(); showReceipt(t); toast(`${t.plate} checked in. Parking clock started at ${t.spotId}.`); await refresh(); }
    catch (error) { fail(error); } finally { button.disabled = false; }
  });

  const change = () => {
    const due = current ? Number(current.currentFee) : 0, got = Number($('gReceived').value);
    $('gChange').textContent = Number.isFinite(got) && got >= due ? money(got - due) : 'Not enough cash';
  };
  $('gReceived').addEventListener('input', change);
  function renderTicket(t) {
    current = t; selected = null;
    $('gateTicket').classList.remove('hidden'); $('gateTicketDetails').replaceChildren(detailCard(t));
    if (t.signature) { const btn = el('button', 'btn btn-plain', 'View signed pass ↗'); btn.type = 'button'; btn.addEventListener('click', () => showReceipt(t)); $('gateTicketDetails').append(btn); }
    const canPay = t.status === 'PARKED' && !t.pendingRef;
    $('cashForm').classList.toggle('hidden', !canPay);
    $('gateDigital').classList.toggle('hidden', !canPay || t.currentFee <= 0);
    $('gatePending').classList.toggle('hidden', !t.pendingRef);
    if (t.pendingRef) $('gatePending').textContent = `Transfer ${t.pendingRef} for ${money(t.pendingFee)} is awaiting admin verification. Do NOT take cash or release the vehicle yet. Refresh to see when the admin confirms it.`;
    if (!canPay) return;
    $('gReceived').value = Number(t.currentFee).toFixed(2); change();
    const mount = $('gateMethods'), box = $('gateDestination'), form = $('gateTransferForm'); box.classList.add('hidden'); form.classList.add('hidden');
    const choose = method => { selected = method; picker(mount, methods, choose, selected.id); destination(box, method, t.currentFee); form.classList.remove('hidden'); };
    picker(mount, methods, choose, null);
    if (!methods.some(m => m.enabled && m.id !== 'CASH')) mount.append(el('div', 'notice-box', 'No merchant transfer accounts are configured. Take cash with a receipt.'));
  }
  const lookup = async quiet => {
    const q = $('gSearch').value.trim(); if (!q) { if (!quiet) toast('Enter a plate or booking code.', true); return; }
    try { renderTicket(await G('/api/guard/lookup?q=' + encodeURIComponent(q))); if (!quiet) toast('Ticket found.'); }
    catch (error) { if (!quiet) fail(error); else if (error.status === 404) $('gateTicket').classList.add('hidden'); }
  };
  $('gateLookupForm').addEventListener('submit', event => { event.preventDefault(); lookup(false); });
  $('cashForm').addEventListener('submit', async event => {
    event.preventDefault(); if (!current || current.status !== 'PARKED' || current.pendingRef) return;
    if (!confirm(`Have you received ${money($('gReceived').value)} cash from the driver? The current fee is ${money(current.currentFee)}.`)) return;
    const button = $('cashForm').querySelector('[type=submit]'); button.disabled = true;
    try { const t = await G('/api/guard/cash-exit', { q: current.id, received: $('gReceived').value });
      renderTicket(t); showReceipt(t); toast(`${t.spotId} released. Cash ${money(t.fee)} recorded. Change ${money(t.cashChange)}.`); await refresh(); }
    catch (error) { fail(error); } finally { button.disabled = false; }
  });
  $('gateTransferForm').addEventListener('submit', async event => {
    event.preventDefault(); if (!current || !selected) return toast('Choose a digital method first.', true);
    const button = $('gateTransferForm').querySelector('[type=submit]'); button.disabled = true;
    try { const t = await request('/api/payments/submit', { id: current.id, plate: current.plate, method: selected.id, reference: $('gateRef').value, lastFour: $('gateLastFour').value });
      renderTicket(t); toast('Sent to admin verification queue. Do not let the vehicle exit until confirmed.'); await refresh(); }
    catch (error) { fail(error); } finally { button.disabled = false; }
  });

  let timer;
  const boot = async () => { const [session, paymentMethods] = await Promise.all([G('/api/guard/session'), request('/api/methods')]);
    $('operatorLabel').textContent = session.operator; methods = paymentMethods; await refresh(); clearInterval(timer);
    timer = setInterval(() => { refresh().catch(() => {}); if (current && current.pendingRef) G('/api/guard/lookup?q=' + encodeURIComponent(current.id)).then(t => { if (t.status !== current.status || t.pendingRef !== current.pendingRef) { renderTicket(t); if (t.status === 'CLOSED') toast('Admin verified the transfer. Show the payment receipt and release the vehicle.'); } }).catch(() => {}); }, 12000);
  };
  if (token) G('/api/guard/session').then(() => { view(true); boot().catch(fail); }).catch(() => logout()); else view(false);
})();
