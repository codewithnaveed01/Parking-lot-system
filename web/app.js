/* Public online booking and externally verified transfer journey. */
(() => {
  'use strict';
  const { $, el, money, fmt, zoneIcon, labels, request, toast, handleError, detailCard, renderZoneMap, picker, destination, showReceipt } = Orbit;
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

  function renderBooking(t) {
    current = t; selected = null;
    $('bookingResult').classList.remove('hidden'); $('bookingResult').replaceChildren(detailCard(t));
    const card = $('bookingResult').firstChild, actions = el('div', 'ticket-actions');
    if (t.signature) { const receipt = el('button', 'btn btn-plain', t.receiptType === 'RESERVATION' ? 'View booking pass ↗' : t.receiptType === 'PAYMENT' ? 'View payment receipt ↗' : 'View entry ticket ↗'); receipt.type = 'button'; receipt.addEventListener('click', () => showReceipt(t)); actions.append(receipt); }
    if (t.status === 'RESERVED') {
      const cancel = el('button', 'btn btn-plain danger-text', 'Cancel reservation'); cancel.type = 'button';
      cancel.addEventListener('click', async () => { if (!confirm(`Cancel reservation ${t.id}? Your bay will return to availability.`)) return;
        try { renderBooking(await request('/api/booking/cancel', { id: t.id, plate: t.plate })); toast('Reservation cancelled; the bay is free again.'); await loadOverview(); } catch (error) { handleError(error); } });
      actions.append(cancel);
      card.append(el('div', 'notice-box', 'Show this code at the gate within 30 minutes.'));
    }
    if (t.status === 'PARKED') {
      if (t.paymentStatus === 'PENDING_VERIFICATION') {
        card.append(el('div', 'warning-box', `Reference ${t.pendingRef} · ${money(t.pendingFee)} submitted via ${t.paymentMethod}. Awaiting admin verification.`));
        const refresh = el('button', 'btn btn-dark', '↻ Check verification status'); refresh.type = 'button'; refresh.addEventListener('click', () => lookup(true)); actions.append(refresh);
      } else if (t.currentFee <= 0) {
        card.append(el('div', 'notice-box', 'Free period — no payment needed.'));
      } else {
        const checkout = el('div', 'payment-checkout'); checkout.append(el('h4', '', `Pay ${money(t.currentFee)} for your stay`));
        const enabled = methods.filter(m => m.id !== 'CASH' && m.enabled);
        if (!enabled.length) checkout.append(el('div', 'notice-box', 'Please pay cash at the gate.'));
        else {
          const choices = el('div', 'payment-picker'), dest = el('div', 'destination-box hidden'), form = el('form', 'hidden');
          form.append(el('label', '', 'Transaction / transfer reference'));
          const ref = el('input'); ref.required = true; ref.maxLength = 40; ref.placeholder = 'From your wallet or bank app'; form.append(ref);
          form.append(el('label', '', 'Sender account last 4 digits (optional)'));
          const last = el('input'); last.maxLength = 4; last.inputMode = 'numeric'; last.placeholder = '1234'; last.pattern = '[0-9]{4}'; form.append(last);
          const submit = el('button', 'btn btn-dark btn-wide', 'Submit transfer for verification →'); submit.type = 'submit'; form.append(submit);
          const choose = method => { selected = method; picker(choices, methods, choose, selected.id); destination(dest, method, t.currentFee); form.classList.remove('hidden'); };
          picker(choices, methods, choose, selected);
          form.addEventListener('submit', async event => { event.preventDefault(); if (!selected) return toast('Choose a transfer method first.', true);
            busy(submit, true);
            try { const next = await request('/api/payments/submit', { id: t.id, plate: t.plate, method: selected.id, reference: ref.value, lastFour: last.value }); renderBooking(next); toast('Reference submitted. Awaiting real transfer verification — this is NOT a payment receipt yet.'); }
            catch (error) { handleError(error); } finally { busy(submit, false); }
          });
          checkout.append(choices, dest, form);
        }
        card.append(checkout);
      }
    }
    if (actions.childNodes.length) card.append(actions);
  }
  const lookup = async quiet => {
    const id = $('lookupId').value.trim(), plate = $('lookupPlate').value.trim();
    if (!id || !plate) { if (!quiet) toast('Enter booking code and license plate.', true); return; }
    try { const t = await request(`/api/booking?id=${encodeURIComponent(id)}&plate=${encodeURIComponent(plate)}`); renderBooking(t); if (!quiet) toast('Booking found.'); }
    catch (error) { if (!quiet) handleError(error); else if (error.status === 404) { current = null; $('bookingResult').classList.add('hidden'); } }
  };

  $('bookingForm').addEventListener('submit', async event => {
    event.preventDefault(); const button = $('bookBtn'); busy(button, true);
    try { const t = await request('/api/book', { type: $('bType').value, plate: $('bPlate').value, owner: $('bOwner').value });
      $('lookupId').value = t.id; $('lookupPlate').value = t.plate;
      renderBooking(t); showReceipt(t); $('bookingForm').reset(); toast(`Bay ${t.spotId} is yours for 30 minutes. Save your booking pass.`);
      await loadOverview();
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
