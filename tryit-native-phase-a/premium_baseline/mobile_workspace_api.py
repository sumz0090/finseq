"""Native D-G workspace API. Shared OMS state; targeted atomic writes only."""
import copy
import csv
import io
import json
import math
import re
import time
import uuid
from datetime import datetime, timezone, timedelta
from urllib.parse import parse_qs, urlparse
from mobile_orders_api import ApiError, token, read, need, commit, date

PREFIX = '/api/mobile/workspace'
CATEGORIES = {'party': 'Party', 'factory': 'Factory', 'art': 'Article', 'colour': 'Colour', 'dml': 'D/M/L', 'material': 'Material', 'box': 'Box', 'sole': 'Sole', 'soleVendor': 'Sole Vendor'}
ORDER_FIELDS = {'party': 'party', 'factory': 'factory', 'art': 'article', 'colour': 'colour', 'dml': 'dml', 'material': 'material', 'box': 'box', 'sole': 'sole'}
PERMISSIONS = [f'{module}_{action}' for module in ('orders', 'production', 'dispatch', 'stock', 'accounts', 'reports', 'masters', 'settings') for action in ('view', 'add', 'edit', 'delete')] + ['production_approve', 'dispatch_approve', 'reports_export']
STAGES = ('Pending', 'In Production', 'Ready', 'Dispatched', 'Cancelled')


def now():
    return datetime.now(timezone(timedelta(hours=5, minutes=30)))


def text(value, limit=500):
    result = str(value or '').strip()
    if len(result) > limit:
        raise ApiError('Field value is too long.')
    return result


def integer(value):
    if isinstance(value, bool) or not re.fullmatch(r'\d+', str(value)) or int(value) > 1000000:
        raise ApiError('Quantities must be whole numbers from 0 to 1000000.')
    return int(value)


def money(value):
    try:
        value = float(value or 0)
    except (TypeError, ValueError):
        raise ApiError('Rate must be a non-negative number.')
    if not math.isfinite(value) or value < 0 or value > 1000000000:
        raise ApiError('Rate must be a non-negative finite number.')
    return value


def sizes(value, positive=False):
    if not isinstance(value, dict):
        raise ApiError('Enter size-wise quantities.')
    result = {'sz' + str(i): integer(value.get('sz' + str(i), 0)) for i in range(4, 15)}
    if positive and not sum(result.values()):
        raise ApiError('Enter at least one pair.')
    return result


def permissions(g, sess):
    return {p: g['_has_perm'](sess, p) for p in PERMISSIONS}


def wrap(value):
    return {'data': value, 'token': token(value)}


def canonical(masters, category, name):
    found = next((m.get('name') for m in masters.get(category, []) if isinstance(m, dict) and str(m.get('name', '')).casefold() == str(name).strip().casefold()), None)
    if not found:
        raise ApiError('Select ' + CATEGORIES[category] + ' from current Masters.')
    return found


def check_token(body, value):
    if body.get('expected_token') != token(value):
        raise ApiError('This record changed on another device. Reload before saving.', 409)


def summary(rows, group='status'):
    result = {}
    for row in rows:
        label = 'Hold' if group == 'status' and row.get('hold') else str(row.get(group) or 'Unspecified')
        r = result.setdefault(label, {'name': label, 'lines': 0, 'pairs': 0, 'groups': set()})
        r['lines'] += 1
        r['pairs'] += int(row.get('totalPairs') or 0)
        r['groups'].add(str(row.get('groupOrderNo') or row.get('orderNo')))
    return [dict(name=x['name'], lines=x['lines'], pairs=x['pairs'], orders=len(x['groups'])) for x in result.values()]


def stock_context(g):
    orders = read(g, 'st_orders', [])
    soles = read(g, 'st_soles', {})
    pos = read(g, 'st_po_log', [])
    boxes = read(g, 'st_boxes', [])
    result = []
    for name, raw in soles.items():
        out = {}
        for size in range(4, 15):
            k = 'sz' + str(size)
            opening = int((raw.get('opening') or {}).get(k) or 0)
            purchase = int((raw.get('purchase') or {}).get(k) or 0)
            consumed = sum(int((o.get('sizes') or {}).get(k) or 0) for o in orders if o.get('sole') == name and not o.get('hold') and (o.get('status') in ('In Production', 'Ready', 'Dispatched') or o.get('status') == 'Cancelled' and o.get('cancelledFromStatus') in ('In Production', 'Ready', 'Dispatched')))
            pending = sum(int((o.get('sizes') or {}).get(k) or 0) for o in orders if o.get('sole') == name and not o.get('hold') and o.get('status') == 'Pending')
            po = sum(max(0, int((e.get('sizes') or {}).get(k) or 0) - int(received_sizes(e).get(k) or 0)) for e in pos if e.get('sole') == name)
            balance = opening + purchase - consumed
            out[k] = {'opening': opening, 'purchase': purchase, 'consumed': consumed, 'pending': pending, 'po': po, 'balance': balance, 'projected': balance + po, 'needOrder': max(0, pending - balance - po)}
        result.append({'name': name, 'data': raw, 'token': token(raw), 'sizes': out, 'totals': {k: sum(v[k] for v in out.values()) for k in ('opening', 'purchase', 'consumed', 'pending', 'po', 'balance', 'projected', 'needOrder')}})
    box_rows = []
    for box in boxes:
        required = sum(int(o.get('totalPairs') or 0) for o in orders if not o.get('hold') and o.get('box') == box.get('name'))
        box_rows.append(dict(wrap(box), required=required, shortage=max(0, required - int(box.get('stock') or 0) - int(box.get('ordered') or 0))))
    return {'soles': result, 'boxes': box_rows, 'pos': [dict(wrap(e), remaining={k: max(0, int(v) - int(received_sizes(e).get(k) or 0)) for k, v in (e.get('sizes') or {}).items()}) for e in pos], 'purchases': [wrap(e) for e in read(g, 'st_purchase_log', [])]}


def received_sizes(entry):
    # Preserve old scalar receive data with the same sequential size allocation
    # used by the web app's ensureReceivedSizes migration.
    if isinstance(entry.get('receivedSizes'), dict) and entry['receivedSizes']:
        return entry['receivedSizes']
    left = max(0, int(entry.get('receivedQty') or 0))
    result = {}
    for size in range(4, 15):
        k = 'sz' + str(size)
        n = min(left, int((entry.get('sizes') or {}).get(k) or 0))
        result[k] = n
        left -= n
    return result


def context(g, sess, module):
    p = permissions(g, sess)
    out = {'ok': True, 'api_version': 2, 'permissions': p, 'is_admin': str(sess.get('role')) == 'admin', 'reset_epoch': g['STATE'].get('reset_epoch', ''), 'version': g['STATE']['version'], 'categories': CATEGORIES}
    if module == 'hub':
        return out
    if module == 'masters':
        if not any(p['masters_' + a] for a in ('view', 'add', 'edit')):
            raise ApiError('Masters access is not permitted.', 403)
        out['masters'] = {k: [wrap(m) for m in rows if isinstance(m, dict)] for k, rows in read(g, 'st_masters', {}).items() if k in CATEGORIES}
    elif module == 'stock':
        if not any(p['stock_' + a] for a in ('view', 'edit', 'add')):
            raise ApiError('Stock access is not permitted.', 403)
        out.update(stock_context(g))
        out['choices'] = {k: [m.get('name', '') for m in read(g, 'st_masters', {}).get(k, []) if isinstance(m, dict)] for k in ('sole', 'soleVendor', 'box')}
    elif module in ('production', 'dispatch', 'business', 'reports'):
        permission = {'production': 'production_view', 'dispatch': 'dispatch_view', 'business': 'accounts_view', 'reports': 'reports_view'}[module]
        need(g, sess, permission)
        rows = read(g, 'st_orders', [])
        out['orders'] = [wrap(o) for o in rows]
        out['summaries'] = {k: summary(rows, k) for k in ('status', 'party', 'factory', 'article', 'colour', 'orderDate')}
        out['batches'] = read(g, 'st_printlog', []) if module == 'production' else []
        if module == 'business':
            out['parties'] = [dict(name=k, **{field: text(m.get(field)) for field in ('code', 'phone', 'email', 'address')}) for k, m in {str(m.get('name', '')): m for m in read(g, 'st_masters', {}).get('party', []) if isinstance(m, dict)}.items()]
    elif module == 'audit':
        if str(sess.get('role')) != 'admin' and not p['reports_view']:
            raise ApiError('Activity access is not permitted.', 403)
        rows = read(g, 'st_audit_log', []) + read(g, 'st_staff_activity', [])
        if str(sess.get('role')) != 'admin':
            rows = [r for r in rows if r.get('by') == sess.get('user')]
        out['activity'] = sorted(rows, key=lambda r: int(r.get('ts') or 0), reverse=True)[:2000]
        if str(sess.get('role')) == 'admin':
            out['access_logs'] = g['_recent_access_logs'](250)
            f = g['CONFIG_AUDIT_FILE']
            if f.exists():
                out['config_logs'] = [json.loads(l) for l in f.read_text('utf-8').splitlines()[-250:] if l.strip()]
    else:
        raise ApiError('Unknown native module.', 404)
    return out


def purchase_entry(sole, qty, body, dest='purchase', source='manual'):
    d = date(body.get('date'), 'Transaction date')
    return {'id': 'pl_' + uuid.uuid4().hex, 'date': d.isoformat(), 'sole': sole, 'sizes': {k: v for k, v in qty.items() if v}, 'totalQty': sum(qty.values()), 'party': text(body.get('party')), 'rate': money(body.get('rate')), 'note': text(body.get('note')), 'source': source, 'dest': dest, 'ts': int(time.time() * 1000)}


def rename_references(g, updates, category, old, new):
    field = ORDER_FIELDS.get(category)
    if field:
        for key in ('st_orders', 'sf_orders'):
            rows = read(g, key, [])
            changed = False
            for row in rows:
                if str(row.get(field, '')).casefold() == old.casefold():
                    row[field] = new
                    changed = True
            if changed:
                updates[key] = rows
    if category == 'sole':
        soles = read(g, 'st_soles', {})
        if old in soles:
            if new in soles:
                raise ApiError('Target sole stock already exists; merge requires a separate review.')
            soles[new] = soles.pop(old)
            updates['st_soles'] = soles
    if category == 'box':
        boxes = read(g, 'st_boxes', [])
        for box in boxes:
            if box.get('name') == old:
                box['name'] = new
        updates['st_boxes'] = boxes
    if category in ('sole', 'soleVendor'):
        for key in ('st_po_log', 'st_purchase_log'):
            rows = read(g, key, [])
            field = 'sole' if category == 'sole' else 'party'
            for row in rows:
                if str(row.get(field, '')).casefold() == old.casefold():
                    row[field] = new
            updates[key] = rows
    # Composite factory/article mapping keys and sole mapping values are part
    # of the same transaction, preserving the web's next-order suggestions.
    for key in ('st_facArtSole', 'st_facArtDml', 'st_facArtMaterial'):
        mapping = read(g, key, {})
        changed = {}
        for mk, mv in mapping.items():
            nk = mk
            if category in ('factory', 'art'):
                parts = mk.split('||')
                index = 0 if category == 'factory' else 1
                if len(parts) == 2 and parts[index].casefold() == old.casefold():
                    parts[index] = new
                nk = '||'.join(parts)
            nv = new if category in ('sole', 'dml', 'material') and key == {'sole': 'st_facArtSole', 'dml': 'st_facArtDml', 'material': 'st_facArtMaterial'}[category] and str(mv).casefold() == old.casefold() else mv
            if nk in changed and changed[nk] != nv:
                raise ApiError('Renaming would conflict with a factory/article mapping.')
            changed[nk] = nv
        if changed != mapping:
            updates[key] = changed


def mutate(g, sess, body):
    if not isinstance(body, dict):
        raise ApiError('A JSON object is required.')
    if body.get('reset_epoch') != g['STATE'].get('reset_epoch', ''):
        raise ApiError('Data was reset/restored. Reload before saving.', 409)
    request = text(body.get('request_id'), 80)
    if not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', request):
        raise ApiError('A unique request ID is required.')
    actor = str(sess.get('user') or '')
    action = body.get('action')
    permission = {'master_create': 'masters_add', 'master_edit': 'masters_edit', 'master_delete': 'masters_delete', 'sole_edit': 'stock_edit', 'box_edit': 'stock_edit', 'po_create': 'stock_edit', 'po_receive': 'stock_edit', 'purchase_edit': 'stock_edit'}.get(action)
    if not permission:
        raise ApiError('Unsupported action.')
    need(g, sess, permission)
    history = read(g, 'st_audit_log', [])
    payload_hash = token({k: v for k, v in body.items() if k != 'confirm_excess'})
    previous = next((r for r in history if r.get('nativeRequestId') == request and r.get('by') == actor), None)
    if previous:
        if previous.get('nativePayloadHash') != payload_hash:
            raise ApiError('Request ID was already used for a different change.', 409)
        return previous.get('nativeResult', {})
    updates = {}
    result = {}
    masters = read(g, 'st_masters', {})
    if action.startswith('master_'):
        category = body.get('category')
        if category not in CATEGORIES:
            raise ApiError('Select a valid master category.')
        rows = masters.setdefault(category, [])
        old = None
        if action != 'master_create':
            old = next((m for m in rows if m.get('name') == body.get('old_name')), None)
            if old is None:
                raise ApiError('Master no longer exists.', 404)
            check_token(body, old)
        if action == 'master_delete':
            if old.get('def'):
                raise ApiError('Default masters cannot be deleted.')
            field = ORDER_FIELDS.get(category)
            if field and any(o.get(field) == old['name'] for key in ('st_orders', 'sf_orders') for o in read(g, key, [])):
                raise ApiError('Master is used by orders and cannot be deleted.')
            for mapping_key in ('st_facArtSole', 'st_facArtDml', 'st_facArtMaterial'):
                mapping = read(g, mapping_key, {})
                used_key = category in ('factory', 'art') and any(len(parts := key.split('||')) == 2 and parts[0 if category == 'factory' else 1].casefold() == old['name'].casefold() for key in mapping)
                used_value = mapping_key == {'sole': 'st_facArtSole', 'dml': 'st_facArtDml', 'material': 'st_facArtMaterial'}.get(category) and any(str(value).casefold() == old['name'].casefold() for value in mapping.values())
                if used_key or used_value:
                    raise ApiError('Master is used by a factory/article mapping and cannot be deleted.')
            if category in ('sole', 'soleVendor') and any(r.get('sole' if category == 'sole' else 'party') == old['name'] for key in ('st_po_log', 'st_purchase_log') for r in read(g, key, [])):
                raise ApiError('Master has transaction history and cannot be deleted.')
            if category == 'sole':
                stock = read(g, 'st_soles', {})
                if any(any(int(v or 0) for v in (stock.get(old['name'], {}).get(k) or {}).values()) for k in ('opening', 'purchase', 'purchaseOrdered')):
                    raise ApiError('Sole has stock and cannot be deleted.')
                stock.pop(old['name'], None); updates['st_soles'] = stock
            if category == 'box':
                boxes = read(g, 'st_boxes', [])
                b = next((b for b in boxes if b.get('name') == old['name']), {})
                if b.get('stock') or b.get('ordered'):
                    raise ApiError('Box has stock and cannot be deleted.')
                updates['st_boxes'] = [b for b in boxes if b.get('name') != old['name']]
            rows.remove(old)
            result = {'name': old['name']}
        else:
            fields = body.get('fields')
            if not isinstance(fields, dict):
                raise ApiError('Master profile fields are required.')
            entry = copy.deepcopy(old or {})
            for field in ('name', 'code', 'phone', 'email', 'address', 'type', 'status', 'notes'):
                entry[field] = text(fields.get(field, entry.get(field, '')))
            if category == 'party':
                rating = integer(fields.get('rating', entry.get('rating', 0)))
                if rating > 3:
                    raise ApiError('Party rating must be from 0 to 3.')
                entry['rating'] = rating
            entry['name'] = entry['name'].upper(); entry['code'] = entry['code'].upper()
            if not entry['name']:
                raise ApiError('Name is required.')
            if any(m is not old and str(m.get('name', '')).casefold() == entry['name'].casefold() for m in rows):
                raise ApiError('A master with this name already exists.', 409)
            if category == 'party' and entry['code'] and any(m is not old and str(m.get('code', '')).casefold() == entry['code'].casefold() for m in rows):
                raise ApiError('Party code is already used.', 409)
            if entry['status'] not in ('active', 'inactive', ''):
                raise ApiError('Status must be active or inactive.')
            entry['status'] = entry['status'] or 'active'
            if entry['email'] and not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+', entry['email']):
                raise ApiError('Enter a valid email address.')
            if old:
                if old['name'] != entry['name']:
                    rename_references(g, updates, category, old['name'], entry['name'])
                rows[rows.index(old)] = entry
            else:
                entry['def'] = False
                rows.append(entry)
                if category == 'sole':
                    stock = read(g, 'st_soles', {})
                    stock.setdefault(entry['name'], {'opening': {}, 'purchase': {}})
                    updates['st_soles'] = stock
                if category == 'box':
                    boxes = read(g, 'st_boxes', [])
                    if not any(b.get('name') == entry['name'] for b in boxes):
                        boxes.append({'name': entry['name'], 'stock': 0, 'ordered': 0})
                    updates['st_boxes'] = boxes
            result = {'name': entry['name']}
        updates['st_masters'] = masters
    elif action == 'sole_edit':
        name = canonical(masters, 'sole', body.get('name'))
        stock = read(g, 'st_soles', {})
        old = stock.get(name, {'opening': {}, 'purchase': {}})
        check_token(body, old)
        kind = body.get('kind')
        if kind not in ('opening', 'purchase'):
            raise ApiError('Select Opening or Purchase stock.')
        wanted = sizes(body.get('sizes'))
        delta = {k: wanted[k] - int((old.get(kind) or {}).get(k) or 0) for k in wanted}
        entry = copy.deepcopy(old); entry[kind] = wanted; stock[name] = entry
        updates['st_soles'] = stock
        logs = read(g, 'st_purchase_log', [])
        if any(delta.values()):
            logs.insert(0, purchase_entry(name, delta, body, kind, 'manual-adjustment' if any(v < 0 for v in delta.values()) else 'manual-opening' if kind == 'opening' else 'manual'))
            updates['st_purchase_log'] = logs
        result = {'name': name}
    elif action == 'box_edit':
        boxes = read(g, 'st_boxes', [])
        old = next((b for b in boxes if b.get('name') == body.get('name')), None)
        if old is None:
            raise ApiError('Box not found.', 404)
        check_token(body, old)
        old['stock'] = integer(body.get('stock')); old['ordered'] = integer(body.get('ordered'))
        updates['st_boxes'] = boxes; result = {'name': old['name']}
    elif action == 'po_create':
        sole = canonical(masters, 'sole', body.get('sole'))
        vendor = canonical(masters, 'soleVendor', body.get('party'))
        qty = sizes(body.get('sizes'), True)
        d = date(body.get('date'), 'PO date')
        pos = read(g, 'st_po_log', [])
        entry = {'id': 'po_' + request, 'sole': sole, 'party': vendor, 'date': d.strftime('%d-%m-%Y'), 'sizes': qty, 'totalQty': sum(qty.values()), 'rate': money(body.get('rate')), 'note': text(body.get('note')), 'batchKey': None, 'receivedQty': 0, 'receivedSizes': {}, 'receiveLog': [], 'ts': int(time.time() * 1000)}
        pos.insert(0, entry)
        updates['st_po_log'] = pos
        stock = read(g, 'st_soles', {}); raw = stock.setdefault(sole, {'opening': {}, 'purchase': {}})
        raw['purchaseOrdered'] = {k: int((raw.get('purchaseOrdered') or {}).get(k) or 0) + v for k, v in qty.items()}
        updates['st_soles'] = stock; result = {'id': entry['id']}
    elif action == 'po_receive':
        pos = read(g, 'st_po_log', [])
        entry = next((e for e in pos if str(e.get('id')) == str(body.get('id'))), None)
        if entry is None:
            raise ApiError('PO not found.', 404)
        check_token(body, entry)
        qty = sizes(body.get('sizes'), True)
        received = copy.deepcopy(received_sizes(entry))
        remaining = {k: max(0, int((entry.get('sizes') or {}).get(k) or 0) - int(received.get(k) or 0)) for k in qty}
        excess = sum(max(0, qty[k] - remaining[k]) for k in qty)
        if excess and body.get('confirm_excess') is not True:
            raise ApiError('Received quantity exceeds this PO. Extra pairs will go to Opening stock. Confirm to continue.', 409, requires_excess_confirmation=True)
        # Only this PO's remaining allocation is moved; other pending POs stay intact.
        stock = read(g, 'st_soles', {}); sole = entry['sole']; raw = stock.setdefault(sole, {'opening': {}, 'purchase': {}})
        bought, extra = {}, {}
        for k, value in qty.items():
            take = min(value, remaining[k]); surplus = value - take
            raw.setdefault('purchase', {})[k] = int(raw.get('purchase', {}).get(k) or 0) + take
            raw.setdefault('opening', {})[k] = int(raw.get('opening', {}).get(k) or 0) + surplus
            raw.setdefault('purchaseOrdered', {})[k] = max(0, int(raw.get('purchaseOrdered', {}).get(k) or 0) - take)
            received[k] = int(received.get(k) or 0) + value
            bought[k] = take; extra[k] = surplus
        d = date(body.get('date'), 'Receive date')
        if d < date(entry['date'], 'PO date'):
            raise ApiError('Receive date cannot precede PO date.')
        entry['receivedSizes'] = received; entry['receivedQty'] = sum(received.values())
        entry.setdefault('receiveLog', []).append({'date': d.isoformat(), 'sizes': bought, 'qty': sum(bought.values()), 'ts': int(time.time() * 1000)})
        logs = read(g, 'st_purchase_log', [])
        receipt = dict(body, party=entry.get('party'), rate=entry.get('rate'), note=entry.get('note'))
        if sum(bought.values()): logs.insert(0, purchase_entry(sole, bought, receipt, source='po-receive'))
        if sum(extra.values()): logs.insert(0, purchase_entry(sole, extra, dict(receipt, note=text(receipt.get('note')) + ' — Extra received'), 'opening', 'po-receive'))
        updates.update(st_soles=stock, st_po_log=pos, st_purchase_log=logs); result = {'id': entry['id']}
    elif action == 'purchase_edit':
        logs = read(g, 'st_purchase_log', [])
        entry = next((e for e in logs if str(e.get('id')) == str(body.get('id'))), None)
        if entry is None: raise ApiError('Purchase entry not found.', 404)
        check_token(body, entry)
        entry.update(party=text(body.get('party')), rate=money(body.get('rate')), note=text(body.get('note')))
        updates['st_purchase_log'] = logs; result = {'id': entry['id']}
    stamp = now()
    history.insert(0, {'id': uuid.uuid4().hex, 'ts': int(time.time() * 1000), 'date': stamp.date().isoformat(), 'time': stamp.strftime('%I:%M:%S %p'), 'by': actor, 'action': action, 'module': 'Masters' if action.startswith('master') else 'Stock', 'record': result.get('name') or result.get('id'), 'details': action.replace('_', ' ').title(), 'nativeRequestId': request, 'nativePayloadHash': payload_hash, 'nativeResult': result})
    updates['st_audit_log'] = history
    commit(g, updates, actor)
    return result


def admin_context(handler, g, sess):
    if str(sess.get('role')) != 'admin':
        raise ApiError('Administration requires an administrator.', 403)
    users = g['_load_user_master']().get('users', {})
    rows = [dict(wrap(g['_public_user'](name, row, include_sensitive=True))) for name, row in sorted(users.items())]
    with g['AUTH_LOCK']:
        sessions = [{'session_id': s.get('session_id', ''), 'username': s.get('user', ''), 'device_id': s.get('device_id', ''), 'device_name': s.get('device_name', ''), 'ip': s.get('ip', ''), 'login_at': s.get('login_at', ''), 'expires': s.get('expires', 0), 'current': tok == handler._cookie_map().get('kiran_oms_user')} for tok, s in g['OMS_SESSIONS'].items() if float(s.get('expires', 0)) > time.time()]
    return {'ok': True, 'is_admin': True, 'users': rows, 'sessions': sessions, 'permission_keys': PERMISSIONS, 'reset_epoch': g['STATE'].get('reset_epoch', '')}


def delegate(handler, path, body):
    previous = handler.path, handler.rfile, handler.headers
    raw = json.dumps(body).encode('utf-8')
    headers = copy.deepcopy(handler.headers)
    headers.replace_header('Content-Length', str(len(raw)))
    try:
        handler.path = path; handler.rfile = io.BytesIO(raw); handler.headers = headers
        handler.do_POST()
    finally:
        handler.path, handler.rfile, handler.headers = previous


def admin_mutation(handler, g, sess, body):
    if str(sess.get('role')) != 'admin':
        raise ApiError('Administration requires an administrator.', 403)
    if not isinstance(body, dict): raise ApiError('A JSON object is required.')
    route = body.get('route')
    if route not in ('users', 'sessions'):
        raise ApiError('Unknown administrator action.')
    payload = body.get('fields')
    if not isinstance(payload, dict): raise ApiError('Action fields are required.')
    with g['AUTH_LOCK']:
        if route == 'users':
            name = g['_norm_username'](payload.get('username'))
            row = g['_load_user_master']().get('users', {}).get(name)
            if row is not None:
                check_token(body, g['_public_user'](name, row, include_sensitive=True))
            elif body.get('expected_token') or payload.get('action') != 'save':
                raise ApiError('User no longer exists.', 404)
            role = payload.get('role')
            if name == 'admin' and payload.get('active') is False:
                raise ApiError('The primary administrator cannot be disabled.')
            if name == sess.get('user') and (payload.get('active') is False or role and role != 'admin'):
                raise ApiError('You cannot disable or demote your current administrator account.')
            perms = payload.get('permissions')
            if perms is not None and (not isinstance(perms, dict) or any(k not in PERMISSIONS or not isinstance(v, bool) for k, v in perms.items())):
                raise ApiError('Invalid permission fields.')
            if payload.get('password') and len(str(payload['password'])) < 8:
                raise ApiError('Password must contain at least 8 characters.')
            if row is None and not payload.get('password'):
                raise ApiError('Set an initial password for the new user.')
        elif payload.get('action') not in ('logout_session', 'logout_user', 'logout_all', 'approve_device', 'revoke_device'):
            raise ApiError('Unknown session/device action.')
        delegate(handler, '/api/admin/' + ('users/manage' if route == 'users' else 'sessions/manage'), payload)


def filtered_rows(g, options):
    result = []
    for o in read(g, 'st_orders', []):
        if any(options.get(k) and o.get(k) != options[k] for k in ('party', 'factory', 'article', 'colour', 'orderDate')): continue
        if options.get('status') and ('Hold' if o.get('hold') else o.get('status')) != options['status']: continue
        if options.get('search') and options['search'].casefold() not in ' '.join(str(o.get(k) or '') for k in ('orderNo', 'groupOrderNo', 'party', 'factory', 'article', 'colour', 'sole', 'box')).casefold(): continue
        if options.get('from') or options.get('to'):
            try: d = date(o.get('orderDate'), 'Order date').isoformat()
            except ApiError: continue
            if options.get('from') and d < options['from']: continue
            if options.get('to') and d > options['to']: continue
        result.append(o)
    return result


def export_csv(handler, g, sess, options):
    need(g, sess, 'reports_export')
    module = options.get('module')
    need(g, sess, 'accounts_view' if module == 'business' else 'reports_view')
    for k in ('from', 'to'):
        if options.get(k):
            try: datetime.strptime(options[k], '%Y-%m-%d')
            except ValueError: raise ApiError('Invalid report date range.')
    if options.get('from') and options.get('to') and options['from'] > options['to']:
        raise ApiError('Report end date cannot precede start date.')
    columns = ('groupOrderNo', 'groupLineNo', 'orderNo', 'orderDate', 'party', 'factory', 'article', 'colour', 'totalPairs', 'status', 'hold', 'prodDate', 'readyDate', 'dispatchDate', 'lastBatch', 'box', 'sole')
    data = io.StringIO(newline=''); writer = csv.writer(data)
    writer.writerow(columns)
    for row in filtered_rows(g, options):
        # Prevent spreadsheet formula injection in exported master names.
        writer.writerow([("'" + str(row.get(k, ''))) if str(row.get(k, '')).startswith(('=', '+', '-', '@')) else row.get(k, '') for k in columns])
    raw = ('\ufeff' + data.getvalue()).encode('utf-8')
    handler.send_response(200); handler.send_header('Content-Type', 'text/csv; charset=utf-8'); handler.send_header('Content-Disposition', 'attachment; filename="TRYIT_Order_Report.csv"'); handler.send_header('Content-Length', str(len(raw))); handler.end_headers(); handler.wfile.write(raw)


def handle(handler, g, method, path):
    # Serialize all old and native admin mutations using the same lock.
    if method == 'POST' and path in ('/api/admin/users/manage', '/api/admin/sessions/manage') and not getattr(handler, '_native_admin_guard', False):
        with g['AUTH_LOCK']:
            handler._native_admin_guard = True
            try: handler.do_POST()
            finally: handler._native_admin_guard = False
        return True
    if not path.startswith(PREFIX + '/'):
        return False
    sess = handler._require_oms_session()
    if not sess: return True
    try:
        if method == 'POST':
            body = handler._read_json()
            if path == PREFIX + '/admin/save':
                admin_mutation(handler, g, sess, body)
                return True
        with g['LOCK']:
            if method == 'GET' and path == PREFIX + '/context':
                query = parse_qs(urlparse(handler.path).query)
                module = query.get('module', ['hub'])[0]
                response = admin_context(handler, g, sess) if module == 'admin' else context(g, sess, module)
                handler.send_json(response)
            elif method == 'POST' and path == PREFIX + '/save':
                handler.send_json({'ok': True, 'result': mutate(g, sess, body), 'message': 'Saved on server.'})
            elif method == 'GET' and path == PREFIX + '/export':
                options = {k: v[0] for k, v in parse_qs(urlparse(handler.path).query).items()}
                export_csv(handler, g, sess, options)
            else:
                raise ApiError('Unknown native API route.', 404)
    except ApiError as error:
        handler.send_json(dict(ok=False, error=str(error), **error.details), error.code)
    except (ValueError, TypeError) as error:
        handler.send_json({'ok': False, 'error': str(error)}, 400)
    except Exception:
        handler.send_json({'ok': False, 'error': 'Server operation failed. No success was confirmed; reload before retrying.'}, 500)
    return True
