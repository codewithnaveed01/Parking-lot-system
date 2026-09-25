#!/usr/bin/env python3
"""End-to-end HTTP checks. Run after compiling: PATH=/path/to/jdk/bin:$PATH python3 test/api_flow.py"""
import datetime as dt
import json
import os
import pathlib
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = os.environ.get('JAVA_BIN', 'java')


def check(condition, label):
    assert condition, label
    print('PASS', label)


def ticket_file(ticket):
    return {field: ticket.get(field) for field in (
        'receiptType', 'id', 'plate', 'owner', 'vehicleType', 'spotId', 'channel', 'createdAt',
        'expiresAt', 'entryTime', 'exitTime', 'pendingAt', 'hourlyRate', 'fee', 'paymentMethod', 'paymentAccount',
        'paymentRef', 'collectedBy', 'cashTendered', 'note', 'signature')}


def main():
    with tempfile.TemporaryDirectory(prefix='orbit-api-test-') as temp:
        root = pathlib.Path(temp)
        (root / 'web').symlink_to(ROOT / 'web', target_is_directory=True)
        (root / 'data').mkdir()
        old = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=61)
        record = f"OLDABC12|CAR|PAID-1|Test Customer|C-40|{old.isoformat().replace('+00:00','Z')}||0||||100.0\n"
        (root / 'data/tickets.db').write_text(record)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        url = f'http://127.0.0.1:{port}'
        env = dict(os.environ, ADMIN_PASS='IntegrationAdmin!2026', GUARD_PASS='IntegrationGuard!2026',
                   RECEIPT_SECRET='integration-test-orbit-signing-key-2026')
        proc = subprocess.Popen([JAVA, '-cp', f'{ROOT}/build:{ROOT}/lib/postgresql-42.7.3.jar', 'com.parking.Main', str(port)],
                                cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            def call(path, body=None, token=None, method=None):
                payload = None if body is None else json.dumps(body).encode()
                headers = {'Accept': 'application/json'}
                if payload is not None: headers['Content-Type'] = 'application/json'
                if token: headers['Authorization'] = 'Bearer ' + token
                request = urllib.request.Request(url + path, data=payload, headers=headers, method=method)
                try:
                    with urllib.request.urlopen(request, timeout=5) as response:
                        raw = response.read().decode()
                        return response.status, json.loads(raw) if 'application/json' in response.headers.get('Content-Type', '') else raw
                except urllib.error.HTTPError as error:
                    return error.code, json.loads(error.read())

            # Small subprocess bootstrap allowance; API checks below fail fast if startup fails.
            for _ in range(30):
                if proc.poll() is not None: raise AssertionError('Server exited during startup: ' + proc.stdout.read().decode())
                try:
                    status, data = call('/api/stats')
                    if status == 200: break
                except (OSError, TimeoutError): time.sleep(.1)
            else: raise AssertionError('Server did not start')
            check(data['totalSpots'] == 100 and data['freeSpots'] == 99, '100 bays incl. restored legacy active vehicle')
            check(call('/api/park', {'plate': 'X-1'})[0] == 404, 'legacy public self-entry endpoint is unavailable')
            check(call('/api/exit', {'plate': 'PAID-1', 'method': 'CASH'})[0] == 409, 'plate exit refuses to release a vehicle with a fee due')
            status, due = call('/api/exit/lookup', {'plate': 'paid 1'})
            check(status == 200 and due['currentFee'] > 0 and 'owner' not in due and 'id' not in due, 'plate lookup shows the fee without exposing owner or booking code')
            check(call('/api/guard/park', {'type': 'CAR', 'plate': 'X-1'})[0] == 401, 'walk-in entry requires guard session')
            check(call('/api/admin/merchant')[0] == 401, 'merchant config requires admin session')
            check(call('/api/admin/login', {'username': 'admin', 'password': 'bad'})[0] == 401, 'wrong admin password rejected')
            admin = call('/api/admin/login', {'username': 'admin', 'password': 'IntegrationAdmin!2026'})[1]['token']
            guard = call('/api/guard/login', {'username': 'guard', 'password': 'IntegrationGuard!2026'})[1]['token']
            check(call('/api/admin/stats', token=guard)[0] == 401, 'gate session cannot access manager controls')
            check(call('/api/guard/session', token=guard)[0] == 200, 'separate gate login works')

            status, reservation = call('/api/book', {'type': 'CAR', 'plate': 'BOOK-001', 'owner': 'Customer'})
            check(status == 200 and reservation['status'] == 'RESERVED' and reservation['spotId'].startswith('C-'), 'online reserves car-only bay for 30 minutes')
            code, plate = reservation['id'], reservation['plate']
            check(call('/api/stats')[1]['reservedSpots'] == 1, 'live inventory includes online hold')
            check(call('/api/booking?id=' + code + '&plate=WRONG')[0] == 404, 'booking needs matching code and plate')
            original_file = ticket_file(reservation)
            check(call('/api/verify', original_file)[1]['valid'], 'reservation JSON signature verifies')
            altered = dict(original_file, owner='Altered owner')
            check(not call('/api/verify', altered)[1]['valid'], 'modified ticket fields fail server verification')
            check(call('/api/admin/spot', {'spotId': reservation['spotId'], 'blocked': 'true'}, admin)[0] == 409, 'admin cannot block held bay')
            status, entered = call('/api/guard/checkin', {'id': code, 'plate': plate}, guard)
            check(status == 200 and entered['status'] == 'PARKED' and entered['entryTime'], 'guard check-in starts metered stay')
            check(call('/api/verify', original_file)[1]['valid'], 'saved reservation remains valid after check-in')
            check(call('/api/guard/checkin', {'id': code, 'plate': plate}, guard)[0] == 409, 'cannot check in twice')
            public_active = call('/api/active')[1]
            check(not any('plate' in item or 'owner' in item for item in public_active), 'public active list hides vehicle identities')
            check(call('/api/payments/submit', {'id': code, 'plate': plate, 'method': 'EASYPAISA', 'reference': 'SENT12345'})[0] == 409,
                  'unconfigured transfer method cannot be used')

            iban = 'PK68HABB0023057903437903'
            config = {'accountName': 'Orbit Park', 'easypaisa': '03001234567', 'jazzcash': '03123456789', 'bankName': 'HBL', 'bankIban': iban}
            check(call('/api/admin/merchant', config, guard)[0] == 401, 'guard cannot configure payment destination')
            check(call('/api/admin/merchant', config, admin)[0] == 200, 'admin configures genuine transfer destinations')
            methods = call('/api/methods')[1]
            check(all(m['enabled'] for m in methods if m['id'] != 'CASH') and not methods[0]['enabled'], 'only configured digital methods are offered online')
            check(call('/api/payments/submit', {'id': code, 'plate': plate, 'method': 'EASYPAISA', 'reference': 'SENT12345'})[0] == 409,
                  'free grace period cannot create fake wallet charge')

            # Legacy paid ticket is already 61 minutes in: billing and verification are exercised over HTTP.
            old_ticket = call('/api/booking?id=OLDABC12&plate=PAID-1')[1]
            check(old_ticket['currentFee'] == 200, 'real time fee uses 61-minute actual duration')
            status, pending = call('/api/payments/submit', {'id': old_ticket['id'], 'plate': old_ticket['plate'],
                                                           'method': 'EASYPAISA', 'reference': 'TRANSFER20260923', 'lastFour': '1234'})
            check(status == 200 and pending['paymentStatus'] == 'PENDING_VERIFICATION' and pending['pendingFee'] == 200,
                  'digital reference is pending, not paid')
            check(call('/api/admin/stats', token=admin)[1]['totalRevenue'] == 0, 'pending transfer not counted as revenue')
            check(call('/api/guard/cash-exit', {'q': old_ticket['id'], 'received': 200}, guard)[0] == 409, 'pending transfer blocks double charge')
            check(call('/api/admin/payment-approve', {'id': old_ticket['id'], 'verified': 'true'}, guard)[0] == 401,
                  'guard cannot approve own transfer')
            check(call('/api/admin/payment-approve', {'id': old_ticket['id']}, admin)[0] == 400, 'admin must explicitly confirm external verification')
            status, paid = call('/api/admin/payment-approve', {'id': old_ticket['id'], 'verified': 'true'}, admin)
            check(status == 200 and paid['paymentStatus'] == 'PAID' and paid['fee'] == 200 and paid['paymentRef'] == 'TRANSFER20260923',
                  'verified payment closes ticket with real submitted reference')
            check(call('/api/verify', ticket_file(paid))[1]['valid'] and paid['pendingAt'], 'signed digital payment receipt keeps its meter cutoff')
            tampered_cutoff = dict(ticket_file(paid), pendingAt='2020-01-01T00:00:00Z')
            check(not call('/api/verify', tampered_cutoff)[1]['valid'], 'modified payment cutoff fails verification')
            check(call('/api/admin/payment-approve', {'id': old_ticket['id'], 'verified': 'true'}, admin)[0] == 409,
                  'same payment cannot be confirmed twice')
            check(call('/api/admin/stats', token=admin)[1]['totalRevenue'] == 200, 'paid receipt counted exactly once')
            check(call('/api/admin/audit?id=' + old_ticket['id'])[0] == 401
                  and call('/api/admin/audit?id=' + old_ticket['id'], token=guard)[0] == 401,
                  'payment audit is manager-only')
            check('TRANSFER_SUBMITTED' in call('/api/admin/audit?id=' + old_ticket['id'], token=admin)[1]['events']
                  and 'TRANSFER_VERIFIED' in call('/api/admin/audit?id=' + old_ticket['id'], token=admin)[1]['events'],
                  'manager sees persistent payment lifecycle events')

            check(call('/api/guard/cash-exit', {'q': code, 'received': 25}, guard)[0] == 400, 'free exit cannot record arbitrary cash')
            status, free = call('/api/guard/cash-exit', {'q': code, 'received': 0}, guard)
            check(status == 200 and free['fee'] == 0 and free['paymentStatus'] == 'NO_CHARGE', 'attended free exit releases bay without fake revenue')
            check(call('/api/verify', ticket_file(free))[1]['valid'], 'free exit receipt has valid signature')
            status, walkin = call('/api/guard/park', {'type': 'TRUCK', 'plate': 'TRK-123', 'owner': 'Driver'}, guard)
            check(status == 200 and walkin['spotId'].startswith('T-'), 'manual entry allocates truck zone')
            check(call('/api/admin/waive', {'q': walkin['id'], 'reason': 'Manager approved complimentary stay'}, guard)[0] == 401,
                  'guard cannot waive charges')
            status, waived = call('/api/admin/waive', {'q': walkin['id'], 'reason': 'Manager approved complimentary stay'}, admin)
            check(status == 200 and waived['fee'] == 0 and waived['note'].startswith('Manager approved'), 'waived release has audit reason and zero revenue')
            check(len(call('/api/admin/history', token=admin)[1]) >= 3 and len(call('/api/admin/vehicles', token=admin)[1]) >= 3,
                  'admin has complete booking and vehicle histories')
            check(call('/api/admin/spot', {'spotId': 'C-01', 'blocked': 'true'}, admin)[0] == 200, 'admin can block free bay')
            check(call('/api/admin/stats', token=admin)[1]['blockedSpots'] == 1, 'blocked bay removed from available count')
            check(call('/admin')[0] == 200 and call('/gate')[0] == 200, 'three independent UI routes are served')
            print('ALL HTTP INTEGRATION TESTS PASSED')
        finally:
            proc.terminate()
            try: proc.wait(timeout=5)
            except subprocess.TimeoutExpired: proc.kill()


if __name__ == '__main__':
    main()
