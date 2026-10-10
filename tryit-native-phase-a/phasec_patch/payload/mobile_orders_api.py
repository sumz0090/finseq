"""Native Orders API: targeted, permission checked, atomic OMS writes."""
import copy
import hashlib
import json
import math
import re
import time
import uuid
from datetime import datetime, timezone, timedelta

PREFIX = '/api/mobile/orders'
FIELDS = {'party', 'partyCode', 'factory', 'article', 'colour', 'dml', 'material', 'box', 'sole', 'rate', 'particular', 'orderDate', 'sizes', 'deliveryDate'}
MASTER_FIELDS = {'party': 'party', 'factory': 'factory', 'article': 'art', 'colour': 'colour', 'dml': 'dml', 'material': 'material', 'box': 'box', 'sole': 'sole'}
PERMS = ['orders_view', 'orders_add', 'orders_edit', 'production_edit', 'production_approve', 'dispatch_approve']


class ApiError(Exception):
    def __init__(self, message, code=400, **details):
        super().__init__(message)
        self.code, self.details = code, details


def token(order):
    return hashlib.sha256(json.dumps(order, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def read(g, key, default):
    return copy.deepcopy(g['_safe_json'](g['STATE']['values'].get(key), default))


def date(value, label):
    try:
        return datetime.strptime(str(value), '%d-%m-%Y').date()
    except (ValueError, TypeError):
        raise ApiError(label + ' must be a valid DD-MM-YYYY date.')


def need(g, sess, permission):
    if not g['_has_perm'](sess, permission):
        raise ApiError('Permission denied: ' + permission, 403)


def validate(fields, masters, old=None):
    if not isinstance(fields, dict):
        raise ApiError('Order fields are required.')
    out = copy.deepcopy(old or {})
    for k in FIELDS - {'sizes', 'rate'}:
        out[k] = str(fields.get(k, out.get(k, ''))).strip()
        if len(out[k]) > 500:
            raise ApiError(k + ' is too long.')
    for k in ('party', 'factory', 'article', 'colour', 'box'):
        if not out[k]:
            raise ApiError(k + ' is required.')
    date(out['orderDate'], 'Order date')
    if out.get('deliveryDate') and date(out['deliveryDate'], 'Delivery date') < date(out['orderDate'], 'Order date'):
        raise ApiError('Delivery date cannot precede the order date.')
    for field, category in MASTER_FIELDS.items():
        value = out.get(field, '')
        if not value or (old and value == old.get(field)):
            continue
        choices = [str(m.get('name', '')) if isinstance(m, dict) else str(m) for m in masters.get(category, [])]
        canonical = next((m for m in choices if m.casefold() == value.casefold()), None)
        if canonical is None:
            raise ApiError(field + ' must be selected from current Masters.')
        out[field] = canonical
    if not out['partyCode']:
        party = next((m for m in masters.get('party', []) if isinstance(m, dict) and str(m.get('name', '')).casefold() == out['party'].casefold()), {})
        out['partyCode'] = str(party.get('code') or '').strip()
    if not out['partyCode']:
        raise ApiError('Party code is required. Set it in Party Masters or enter it here.')
    sizes = fields.get('sizes', out.get('sizes', {}))
    if not isinstance(sizes, dict):
        raise ApiError('Size quantities are required.')
    out['sizes'] = {}
    for size in range(4, 15):
        value = sizes.get('sz' + str(size), 0)
        if isinstance(value, bool) or not re.fullmatch(r'\d+', str(value)) or int(value) > 1000000:
            raise ApiError('Size ' + str(size) + ' quantity must be a non-negative whole number.')
        out['sizes']['sz' + str(size)] = int(value)
    out['totalPairs'] = sum(out['sizes'].values())
    if out['totalPairs'] <= 0:
        raise ApiError('Enter at least one pair.')
    try:
        out['rate'] = float(fields.get('rate', out.get('rate', 0)) or 0)
    except (TypeError, ValueError):
        raise ApiError('Rate must be a valid number.')
    if not math.isfinite(out['rate']) or out['rate'] < 0:
        raise ApiError('Rate must be non-negative.')
    if old:
        for key in ('prodDate', 'readyDate', 'dispatchDate'):
            if old.get(key) and date(out['orderDate'], 'Order date') > date(old[key], key):
                raise ApiError('Order date cannot follow an existing lifecycle date.')
    return out


def duplicate(rows, candidate, excluding=None):
    for o in rows:
        if str(o.get('orderNo')) == str(excluding) or o.get('status') in ('Dispatched', 'Cancelled'):
            continue
        if all(str(o.get(k, '')).strip().casefold() == str(candidate.get(k, '')).strip().casefold() for k in ('party', 'factory', 'article', 'colour')):
            return o.get('orderNo')
    return None


def commit(g, values, actor):
    previous = copy.deepcopy(g['STATE'])
    try:
        state = g['STATE']
        for key, value in values.items():
            state['version'] += 1
            state['values'][key] = json.dumps(value, ensure_ascii=False)
            state['key_versions'][key] = state['version']
            state['key_clients'][key] = 'native:' + actor
        state['updated_at'] = g['now_iso']()
        g['atomic_save_state']()
    except Exception:
        g['STATE'].clear()
        g['STATE'].update(previous)
        raise


def audit(action, order, actor, now, detail, old='', new=''):
    return {'id': uuid.uuid4().hex, 'ts': int(now.timestamp() * 1000), 'date': now.date().isoformat(), 'time': now.strftime('%I:%M:%S %p'), 'by': actor, 'action': action, 'module': 'Order', 'record': str(order['orderNo']), 'details': detail, 'oldValue': old, 'newValue': new}


def event(action, o, actor, now, action_date):
    row = {k: o.get(k, '') for k in ('orderNo', 'groupOrderNo', 'groupLineNo', 'party', 'factory', 'article', 'colour', 'box', 'sole', 'orderDate', 'totalPairs')}
    row.update(id=uuid.uuid4().hex, action=action, date=action_date, time=now.strftime('%I:%M %p'), by=actor, statusAtEvent=action)
    return row


def mutate(g, sess, body):
    action = body.get('action')
    actor = str(sess.get('user') or 'unknown')
    if body.get('reset_epoch') != g['STATE'].get('reset_epoch', ''):
        raise ApiError('Live data was reset/restored. Reload before saving.', 409)
    orders = read(g, 'st_orders', [])
    masters = read(g, 'st_masters', {})
    if not isinstance(orders, list) or not isinstance(masters, dict):
        raise ApiError('Server data is invalid. Contact the administrator.', 500)
    now = datetime.now(timezone(timedelta(hours=5, minutes=30)))
    stamp = now.isoformat(timespec='milliseconds')
    history = read(g, 'st_audit_log', [])
    events = read(g, 'st_status_event_history', [])
    updates = {}
    result = []
    if action == 'create':
        need(g, sess, 'orders_add')
        rid = str(body.get('request_id') or '')
        if not re.fullmatch(r'[a-zA-Z0-9-]{16,80}', rid):
            raise ApiError('A valid request identifier is required.')
        previous = [o for o in orders if o.get('mobileRequestId') == rid and o.get('mobileRequestActor') == actor]
        request_hash = token(body.get('lines'))
        if previous:
            if previous[0].get('mobileRequestHash') != request_hash:
                raise ApiError('Request identifier was already used. Reload the form.', 409)
            return previous
        lines = body.get('lines')
        if not isinstance(lines, list) or not 1 <= len(lines) <= 50:
            raise ApiError('Create between 1 and 50 order lines.')
        candidates = [validate(line, masters) for line in lines]
        if any(any(c.get(k) != candidates[0].get(k) for k in ('party', 'partyCode', 'factory', 'orderDate')) for c in candidates):
            raise ApiError('All lines must share party, factory and order date.')
        existing_ids = [int(o['orderNo']) for o in orders if str(o.get('orderNo', '')).isdigit()]
        number = max(existing_ids, default=0) + 1
        group = max([int(o.get('groupOrderNo') or o.get('orderNo') or 0) for o in orders if str(o.get('groupOrderNo') or o.get('orderNo') or 0).isdigit()], default=0) + 1
        for i, o in enumerate(candidates):
            dup = duplicate(orders + result, o)
            if dup is not None and body.get('allow_duplicate') is not True:
                raise ApiError('A similar open order already exists: #' + str(dup) + '. Confirm to save another.', 409, requires_duplicate_confirmation=True)
            o.update(orderNo=number + i, groupOrderNo=group, groupLineNo=i + 1, status='Pending', printed='', isNew=True, createdAt=stamp, statusChangedAt=stamp, prodDate='', readyDate='', dispatchDate='', hold=False, mobileRequestId=rid, mobileRequestActor=actor, mobileRequestHash=request_hash)
            result.append(o)
            history.insert(0, audit('Create', o, actor, now, 'Native order created' + ('; duplicate explicitly confirmed' if dup is not None else ''), '', 'Pending'))
            events.insert(0, event('Pending', o, actor, now, o['orderDate']))
        orders.extend(result)
    elif action in ('edit', 'status'):
        need(g, sess, 'orders_view')
        old = next((o for o in orders if str(o.get('orderNo')) == str(body.get('order_no'))), None)
        if old is None:
            raise ApiError('Order no longer exists. Reload the order list.', 404)
        if token(old) != body.get('expected_token'):
            raise ApiError('This order changed on another device. Reload before saving.', 409)
        if action == 'edit':
            need(g, sess, 'orders_edit')
            new = validate(body.get('fields'), masters, old)
            dup = duplicate(orders, new, old['orderNo'])
            if dup is not None and body.get('allow_duplicate') is not True:
                raise ApiError('A similar open order exists: #' + str(dup) + '. Confirm to continue.', 409, requires_duplicate_confirmation=True)
            changes = {k: {'before': old.get(k), 'after': new.get(k)} for k in FIELDS if old.get(k) != new.get(k)}
            history.insert(0, audit('Update', new, actor, now, 'Native edit: ' + json.dumps(changes, ensure_ascii=False)))
        else:
            new = copy.deepcopy(old)
            target = str(body.get('target') or '')
            current = str(old.get('status') or 'Pending')
            if target in ('Hold', 'Resume'):
                need(g, sess, 'orders_edit')
                if current in ('Dispatched', 'Cancelled'):
                    raise ApiError('Closed orders cannot be held or resumed.')
                new.update(hold=target == 'Hold', holdDate=now.strftime('%d-%m-%Y') if target == 'Hold' else '')
            else:
                allowed = {'Pending': ['In Production', 'Cancelled'], 'In Production': ['Ready', 'Dispatched', 'Cancelled'], 'Ready': ['Dispatched', 'Cancelled'], 'Dispatched': ['Cancelled'], 'Cancelled': []}
                if target not in allowed.get(current, []):
                    raise ApiError('This status transition is not allowed.')
                need(g, sess, {'In Production': 'production_edit', 'Ready': 'production_approve', 'Dispatched': 'dispatch_approve', 'Cancelled': 'orders_edit'}[target])
                if old.get('hold') and target != 'Cancelled':
                    raise ApiError('Resume this held order before advancing its status.')
                ad = date(body.get('action_date'), 'Action date')
                previous_dates = ['orderDate'] + (['prodDate'] if target in ('Ready', 'Dispatched') else []) + (['readyDate'] if target == 'Dispatched' else [])
                if any(old.get(k) and ad < date(old[k], k) for k in previous_dates):
                    raise ApiError('Action date cannot precede an earlier lifecycle stage.')
                if target == 'In Production' and not old.get('lastBatch') and body.get('confirm_without_batch') is not True:
                    raise ApiError('No batch number is recorded. Confirm direct production first.', 409, requires_batch_confirmation=True)
                action_date = ad.strftime('%d-%m-%Y')
                new.update(status=target, statusChangedAt=stamp)
                if target == 'In Production':
                    new['prodDate'] = action_date
                    if not read(g, 'sf_sync_paused', False):
                        sf = read(g, 'sf_orders', [])
                        if not any(str(o.get('_stOrderNo')) == str(new['orderNo']) or all(str(o.get(k)) == str(new.get(v)) for k, v in (('party', 'party'), ('article', 'article'), ('colour', 'colour'), ('_stFactory', 'factory'))) for o in sf):
                            sf.append({'id': 'ST' + str(new['orderNo']) + '_' + str(int(now.timestamp() * 1000)), 'date': now.strftime('%d/%m/%Y'), 'party': new['party'], 'article': str(new['article']), 'colour': new['colour'], 'leather': '', 'sole': new.get('sole', ''), 'last': '', 'finish': '', 'delivery': '', 'sizes': {str(s): new['sizes'].get('sz' + str(s), 0) for s in range(4, 15)}, 'status': 'Pending', 'stageWorkers': {}, '_stOrderNo': new['orderNo'], '_stFactory': new['factory'], '_stBox': new['box'], '_stPartyCode': new['partyCode'], '_stSentAt': stamp, '_stSentAtMs': int(now.timestamp() * 1000)})
                            updates['sf_orders'] = sf
                elif target == 'Ready':
                    new['readyDate'] = action_date
                elif target == 'Dispatched':
                    new.update(dispatchDate=action_date, dispatchDateChangedAt=stamp)
                    if current == 'In Production' and not new.get('readyDate'):
                        new['readyDate'] = action_date
                        events.insert(0, event('Ready', new, actor, now, action_date))
                elif target == 'Cancelled':
                    new['cancelledFromStatus'] = current
                    new['hold'] = False
                events.insert(0, event(target, new, actor, now, action_date))
            history.insert(0, audit(target, new, actor, now, 'Native status action', current, target))
        orders[orders.index(old)] = new
        result = [new]
    else:
        raise ApiError('Unsupported order action.')
    for o in result:
        o.update(updatedAt=stamp, lastEdited=now.strftime('%d %b %Y, %I:%M %p'), deviceId=str(sess.get('device_id') or 'native'), lastEditedBy=actor)
    updates.update(st_orders=orders, st_audit_log=history, st_status_event_history=events)
    commit(g, updates, actor)
    return result


def handle(handler, g, method, path):
    if not path.startswith(PREFIX):
        return False
    sess = handler._require_oms_session()
    if not sess:
        return True
    try:
        with g['LOCK']:
            if method == 'GET' and path == PREFIX + '/context':
                permissions = {p: g['_has_perm'](sess, p) for p in PERMS}
                if not any(permissions.values()):
                    raise ApiError('Orders access is not permitted.', 403)
                orders = read(g, 'st_orders', []) if permissions['orders_view'] else []
                masters = read(g, 'st_masters', {}) if permissions['orders_add'] or permissions['orders_edit'] else {}
                # Only picker values; do not expose unrelated master profile fields.
                choices = {k: [{'name': str(m.get('name', '')), 'code': str(m.get('code', '')) if k == 'party' else ''} for m in masters.get(k, []) if isinstance(m, dict)] for k in MASTER_FIELDS.values()}
                handler.send_json({'ok': True, 'api_version': 1, 'permissions': permissions, 'orders': [{'data': o, 'token': token(o)} for o in orders], 'masters': choices, 'reset_epoch': g['STATE'].get('reset_epoch', ''), 'version': g['STATE']['version'], 'history': [a for a in read(g, 'st_audit_log', []) if str(a.get('module', '')).startswith('Order')][:1000] if permissions['orders_view'] else [], 'events': read(g, 'st_status_event_history', [])[:1000] if permissions['orders_view'] else []})
            elif method == 'POST' and path == PREFIX + '/save':
                result = mutate(g, sess, handler._read_json())
                handler.send_json({'ok': True, 'orders': [{'data': o, 'token': token(o)} for o in result], 'version': g['STATE']['version'], 'message': 'Saved on server.'})
            else:
                raise ApiError('Mobile Orders endpoint not found.', 404)
    except ApiError as e:
        handler.send_json({'ok': False, 'error': str(e), **e.details}, e.code)
    except ValueError as e:
        handler.send_json({'ok': False, 'error': str(e)}, 400)
    except Exception:
        handler.send_json({'ok': False, 'error': 'Server could not save this action. Reload before retrying.'}, 500)
    return True
