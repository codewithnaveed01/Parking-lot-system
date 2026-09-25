/* Public online booking and externally verified transfer journey. */
(() => {
  'use strict';
  const { $, el, money, fmt, zoneIcon, labels, request, toast, handleError, detailCard, renderZoneMap, picker, destination, showReceipt, onlinePayment, barrierOpen } = Orbit;
  const zoneOrder = ['MOTORCYCLE', 'CAR', 'VAN', 'TRUCK', 'BUS'];
  let methods = [], current = null, selected = null;
  const busy = (button, on) => { button.disabled = on; button.dataset.busy = on ? '1' : '0'; };

  const loadOverview = async () => {
    const [stats, zones] = await Promise.all([request('/api/stats'), request('/api/spots')]);
    $('sTotal').textContent = stats.totalSpots; $('sFree').textContent = stats.freeSpots;
    $('sReserved').textContent = stats.reservedSpots; $('sOccupied').textContent = stats.occupiedSpots;
    renderZoneMap($('zonesMap'), zones);
    $('zoneCards').replaceChildren();
    zoneOrder.forEach((type, idx) => {
      const values = stats.zones[type], card = el('article', 'zone-card');
      const top = el('div', 'zone-top'), icon = el('span', 'zone-icon'); icon.append(zoneIcon(type));
      top.append(icon);
      card.append(top, el('h3', '', labels[type]));
      const count = el('div', 'zone-number'); count.append(el('strong', '', values.free), el('span', '', `of ${values.total} available`)); card.append(count);
      const track = el('div', 'zone-progress'); const bar = el('i'); bar.style.width = `${Math.max(0, Math.min(100, values.free / values.total * 100))}%`; track.append(bar); card.append(track);
      const foot = el('div', 'zone-foot'); foot.append(el('span', '', `${values.reserved} held · ${values.occupied} parked`));
      const link = el('a', '', 'Book →'); link.href = '#booking'; link.addEventListener('click', () => { $('bType').value = type; });
      foot.append(link); card.append(foot); $('zoneCards').append(card);
    });
  };
  const loadRates = async () => {
    const rates = await request('/api/rates'); $('ratesList').replaceChildren();
    rates.forEach(rate => { const row = el('div', 'rate-line'); row.append(el('span', '', rate.label), el('strong', '', `${money(rate.rate)} / hr`)); $('ratesList').append(row); });
  };
  const loadMethods = async () => { methods = await request('/api/methods'); };

  const exitButton = (t, label) => {
    const button = el('button', 'btn btn-red', label); button.type = 'button';
    button.addEventListener('click', async () => { if (!confirm(`Exit bay ${t.spotId} now?`)) return; busy(button, true);
      try { const done = await request('/api/booking/exit', { id: t.id, plate: t.plate }); renderBooking(done); loadOverview(); barrierOpen(done, () => showReceipt(done)); }
      catch (error) { handleError(error); } finally { busy(button, false); } });
    return button;
  };
  /* Simple exit: number plate -> pay -> exit. */
  const exitStep = n => document.querySelectorAll('.exit-steps li').forEach(li => li.classList.toggle('active', Number(li.dataset.step) <= n));
  const exitDone = (box, t) => {
    exitStep(3); box.replaceChildren();
    const paid = t.fee || t.pendingFee || 0;
    const done = el('div', 'exit-done'); done.append(el('b', '', 'Exit complete ✓'), el('span', '', `${t.plate} · bay ${t.spotId} · ${paid > 0 ? money(paid) + ' paid' : 'no charge'}`));
    const receipt = el('button', 'btn btn-plain', 'View receipt'); receipt.type = 'button'; receipt.addEventListener('click', () => showReceipt(t));
    done.append(receipt); box.append(done); $('exitPlate').value = ''; loadOverview();
    barrierOpen(t, () => showReceipt(t));
  };
  /* Self exit is online only (TID or receipt photo). Cash is taken only by the guard at the barrier. */
  const paymentChoice = (mount, { due, plate, onPaid }) => {
    const body = el('div'); mount.append(body);
    const ready = onlinePayment(body, { methods, due, submitLabel: `Pay ${money(due)} & exit`, onSubmit: async proof => {
      try { onPaid(await request('/api/exit/pay', { plate, method: proof.method, reference: proof.reference, lastFour: '', receipt: proof.receipt })); }
      catch (error) { handleError(error); } } });
    if (!ready) body.replaceChildren(el('div', 'notice-box', 'Online payment is not available right now. Please pay at the guard room at the barrier.'));
  };
  const renderExit = info => {
    const box = $('exitResult'); box.replaceChildren(); exitStep(2);
    const summary = el('div', 'exit-summary');
    [['Plate', info.plate], ['Bay', info.spotId], ['Parked since', fmt(info.entryTime)], ['Amount due', info.pending ? 'Paid online' : money(info.currentFee)]]
      .forEach(([k, v]) => { const d = el('div'); d.append(el('span', '', k), el('strong', '', v)); summary.append(d); });
    box.append(summary);
    if (info.pending || info.currentFee <= 0) {
      exitStep(3);
      const go = el('button', 'btn btn-red btn-wide', info.pending ? 'Open barrier & exit' : 'Exit now — free'); go.type = 'button';
      go.addEventListener('click', async () => { busy(go, true);
        try { exitDone(box, await request('/api/exit', { plate: info.plate })); } catch (error) { handleError(error); } finally { busy(go, false); } });
      box.append(go); return;
    }
    paymentChoice(box, { due: info.currentFee, plate: info.plate, onPaid: t => exitDone(box, t) });
  };
  $('exitForm').addEventListener('submit', async event => {
    event.preventDefault(); const button = $('exitFind'); busy(button, true);
    try { renderExit(await request('/api/exit/lookup', { plate: $('exitPlate').value })); }
    catch (error) { $('exitResult').replaceChildren(); exitStep(1); handleError(error); } finally { busy(button, false); }
  });

  function renderBooking(t) {
    current = t; selected = null;
    $('bookingResult').classList.remove('hidden'); $('bookingResult').replaceChildren(detailCard(t));
    const card = $('bookingResult').firstChild, actions = el('div', 'ticket-actions');
    if (t.signature) { const receipt = el('button', 'btn btn-plain', t.receiptType === 'RESERVATION' ? 'View booking pass ↗' : t.receiptType === 'PAYMENT' ? 'View payment receipt ↗' : 'View entry ticket ↗'); receipt.type = 'button'; receipt.addEventListener('click', () => showReceipt(t)); actions.append(receipt); }
    if (t.status === 'RESERVED') {
      const cancel = el('button', 'btn btn-plain danger-text', 'Cancel reservation'); cancel.type = 'button';
      cancel.addEventListener('click', async () => { if (!confirm(`Cancel reservation ${t.id}? Your bay will return to availability.`)) return;
        try { renderBooking(await request('/api/booking/cancel', { id: t.id, plate: t.plate })); toast('Reservation cancelled; the bay is free again.'); await loadOverview(); } catch (error) { handleError(error); } });
      const arrive = el('button', 'btn btn-red', "I've arrived — Check in"); arrive.type = 'button';
      arrive.addEventListener('click', async () => { busy(arrive, true);
        try { renderBooking(await request('/api/booking/checkin', { id: t.id, plate: t.plate })); toast('Checked in. Meter started.'); await loadOverview(); }
        catch (error) { handleError(error); } finally { busy(arrive, false); } });
      actions.prepend(arrive); actions.append(cancel);
      card.append(el('div', 'notice-box', 'Arrive within 30 minutes and check in here or at the gate.'));
    }
    if (t.status === 'PARKED') {
      if (t.paymentStatus === 'PENDING_VERIFICATION') {
        card.append(el('div', 'warning-box', `Reference ${t.pendingRef} · ${money(t.pendingFee)} submitted via ${t.paymentMethod}. You can exit now.`));
        actions.prepend(exitButton(t, 'Exit now'));
        const refresh = el('button', 'btn btn-dark', '↻ Check verification status'); refresh.type = 'button'; refresh.addEventListener('click', () => lookup(true)); actions.append(refresh);
      } else if (t.currentFee <= 0) {
        card.append(el('div', 'notice-box', 'Free period — no payment needed.'));
        actions.prepend(exitButton(t, 'Exit now (free)'));
      } else {
        const checkout = el('div', 'payment-checkout'); checkout.append(el('h4', '', `Pay ${money(t.currentFee)} and exit`));
        paymentChoice(checkout, { due: t.currentFee, plate: t.plate, onPaid: done => { renderBooking(done); loadOverview(); barrierOpen(done, () => showReceipt(done)); } });
        card.append(checkout);
      }
    }
    if (t.status === 'CLOSED' && t.paymentStatus === 'PENDING_VERIFICATION') card.append(el('div', 'notice-box', 'Exited. Your online payment is being verified.'));
    if (t.paymentStatus === 'UNPAID_AFTER_EXIT') card.append(el('div', 'warning-box', `Payment not received — ${money(t.fee)} is still due. ${t.note || ''}`));
    if (actions.childNodes.length) card.append(actions);
  }
  const lookup = async quiet => {
    const id = $('lookupId').value.trim(), plate = $('lookupPlate').value.trim();
    if (!id || !plate) { if (!quiet) toast('Enter booking code and license plate.', true); return; }
    try { const t = await request(`/api/booking?id=${encodeURIComponent(id)}&plate=${encodeURIComponent(plate)}`); renderBooking(t); if (!quiet) toast('Booking found.'); }
    catch (error) { if (!quiet) handleError(error); else if (error.status === 404) { current = null; $('bookingResult').classList.add('hidden'); } }
  };

  const mode = () => (document.querySelector('input[name=bMode]:checked') || {}).value || 'reserve';
  const syncMode = () => { $('bookBtn').textContent = mode() === 'park' ? 'Park now' : 'Reserve a spot'; };
  document.querySelectorAll('input[name=bMode]').forEach(r => r.addEventListener('change', syncMode)); syncMode();
  $('bookingForm').addEventListener('submit', async event => {
    event.preventDefault(); const button = $('bookBtn'); busy(button, true);
    const parkNow = mode() === 'park';
    try { const t = await request(parkNow ? '/api/park-now' : '/api/book', { type: $('bType').value, plate: $('bPlate').value, owner: $('bOwner').value });
      $('lookupId').value = t.id; $('lookupPlate').value = t.plate;
      renderBooking(t); showReceipt(t); $('bookingForm').reset(); syncMode();
      toast(parkNow ? `Parked at bay ${t.spotId}. Meter started.` : `Bay ${t.spotId} is held for 30 minutes.`);
      await loadOverview();
      $('manage').scrollIntoView({ behavior: 'smooth' });
    } catch (error) { handleError(error); } finally { busy(button, false); }
  });
  $('lookupForm').addEventListener('submit', event => { event.preventDefault(); lookup(false); });
  $('verifyFile').addEventListener('change', async event => {
    const file = event.target.files[0], out = $('verifyResult'); out.replaceChildren(); event.target.value = '';
    if (!file) return;
    if (file.size > 20000) return toast('Ticket file is too large.', true);
    try { const saved = JSON.parse(await file.text());
      if (!saved || typeof saved !== 'object' || Array.isArray(saved) || !['Salim Habib Parking','Orbit Park'].includes(saved.system)) throw new Error('This is not an Salim Habib Parking ticket JSON file.');
      const result = await request('/api/verify', saved);
      if (!result.valid) { out.append(el('strong', '', '✕ Ticket information or signature does not match our records.')); toast('Ticket could not be verified.', true); return; }
      out.append(el('strong', '', `✓ Genuine ${saved.receiptType.toLowerCase()} ticket · ${saved.id} · ${result.ticket.status.toLowerCase()} now. `));
      if (result.ticket.signature) { const view = el('button', 'btn btn-outline-white', 'View current pass'); view.type = 'button'; view.addEventListener('click', () => showReceipt(result.ticket)); out.append(view); }
      $('lookupId').value = result.ticket.id; $('lookupPlate').value = result.ticket.plate; renderBooking(result.ticket);
      toast('Ticket signature verified against Salim Habib Parking records.');
    } catch (error) { handleError(error); }
  });
  Promise.all([loadOverview(), loadRates(), loadMethods()]).catch(handleError);
  setInterval(() => {
    loadOverview().catch(() => {});
    if (current && (current.paymentStatus === 'PENDING_VERIFICATION' || current.status === 'RESERVED')) lookup(true);
  }, 15000);
})();
