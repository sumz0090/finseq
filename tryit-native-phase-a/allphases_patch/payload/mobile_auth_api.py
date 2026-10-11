"""Revocable remembered-device login. No passwords or plaintext tokens are persisted."""
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import time

TTL = 90 * 86400


def _file(g):
    return g['SECURITY_DIR'] / 'native_remembered_devices.json'


def _load(g):
    p = _file(g)
    if not p.exists(): return {}
    # Corrupt security records fail closed; never replace them with fresh trust.
    data = json.loads(p.read_text('utf-8'))
    if not isinstance(data, dict): raise ValueError('Invalid remembered-device records')
    return data


def _save(g, data):
    p = _file(g); p.parent.mkdir(parents=True, exist_ok=True)
    temp = p.with_name(p.name + '.' + secrets.token_hex(8) + '.tmp')
    try:
        temp.write_text(json.dumps(data, indent=2), 'utf-8')
        try: os.chmod(temp, 0o600)
        except OSError: pass
        os.replace(temp, p)
    finally:
        if temp.exists(): temp.unlink()


def revoke(g, username=None, device_id=None, keep=None):
    with g['AUTH_LOCK']:
        data = _load(g)
        for key, row in list(data.items()):
            if keep and (row['user'], row['device_id']) == keep: continue
            if (username is None or row['user'] == username) and (device_id is None or row['device_id'] == device_id):
                data.pop(key)
        _save(g, data)


def revoke_session(g, session_id):
    if not session_id: return
    with g['AUTH_LOCK']:
        data = _load(g)
        for key, row in list(data.items()):
            if row.get('session_id') == session_id: data.pop(key)
        _save(g, data)


def _fingerprint(g, user, row):
    # Also catches legacy admin password changes that do not bump session_version.
    password = row.get('password_hash', '')
    if user == 'admin': password = g['ADMIN_MASTER_FILE'].read_text('utf-8') if g['ADMIN_MASTER_FILE'].exists() else ''
    return hashlib.sha256(str(password).encode()).hexdigest()


def _eligible(g, handler, user, row, device):
    if not row or not row.get('active', True) or row.get('deleted_at') or row.get('must_change_password'): return False
    ip = g['_request_ip'](handler)
    allowed = row.get('allowed_ips', [])
    if allowed and ip not in allowed: return False
    try: local = ipaddress.ip_address(ip).is_private or ipaddress.ip_address(ip).is_loopback
    except ValueError: local = False
    if row.get('allow_wan', True) is False and not local: return False
    if row.get('restrict_devices') and not g['_find_device'](g['_device_rows'](row, 'approved_devices'), device): return False
    return True


def handle_mobile_auth(h, g, method, path):
    if method != 'POST' or path not in ('/api/mobile/auth/enroll', '/api/mobile/auth/refresh', '/api/mobile/auth/revoke'): return False
    try:
        body = h._read_json()
        with g['AUTH_LOCK']:
            data = _load(g)
            if path.endswith('/refresh') or path.endswith('/revoke'):
                secret = str(body.get('token') or '')
                device = str(body.get('device_id') or '')[:128]
                key = hashlib.sha256(secret.encode()).hexdigest()
                rec = data.get(key)
                if len(secret) < 40 or not rec or not hmac.compare_digest(rec['device_id'], device):
                    h.send_json({'ok': False, 'error': 'Device login expired or revoked. Please sign in.'}, 401); return True
                if path.endswith('/revoke'):
                    data.pop(key); _save(g, data); h.send_json({'ok': True}); return True
                user = rec['user']; row = g['_load_user_master']().get('users', {}).get(user, {})
                good = (rec['expires'] > time.time() and _eligible(g, h, user, row, device)
                        and rec['role'] == row.get('role') and rec['session_version'] == int(row.get('session_version', 0) or 0)
                        and hmac.compare_digest(rec['password_fingerprint'], _fingerprint(g, user, row)))
                if not good:
                    data.pop(key); _save(g, data)
                    h.send_json({'ok': False, 'error': 'Device login expired or revoked. Please sign in.'}, 401); return True
                now = time.time(); hours = max(1, min(72, int(g['CONFIG'].get('session_hours', 8) or 8)))
                if row.get('single_device'):
                    # A newer credential login elsewhere wins; never revive an older device.
                    other = [r for r in data.values() if r['user'] == user and r['device_id'] != device]
                    if other:
                        h.send_json({'ok': False, 'error': 'Please sign in on this device again.'}, 401); return True
                token = secrets.token_urlsafe(32)
                # Replace this device's session to avoid unlimited session creation.
                for tok, sess in list(g['OMS_SESSIONS'].items()):
                    if sess.get('user') == user and sess.get('device_id') == device: g['OMS_SESSIONS'].pop(tok)
                g['OMS_SESSIONS'][token] = {'session_id': rec['session_id'], 'user': user, 'role': row['role'], 'expires': now + hours * 3600, 'login_at': g['now_iso'](), 'last_seen': now, 'ip': g['_request_ip'](h), 'device_id': device, 'device_name': rec['device_name'], 'session_version': rec['session_version']}
                cookie = f'kiran_oms_user={token}; Path=/; HttpOnly; SameSite=Strict; Max-Age={hours * 3600}'
                if h._secure_cookie(): cookie += '; Secure'
                g['append_config_audit']('native_device_session_renewed', {'device_id': device}, actor=user)
                h.send_json({'ok': True, 'profile': g['_public_user'](user, row)}, extra_headers={'Set-Cookie': cookie}); return True
            sess = h._oms_session()
            if not sess:
                h.send_json({'ok': False, 'error': 'Sign in is required.'}, 401); return True
            user = sess['user']; device = str(body.get('device_id') or '')[:128]
            row = g['_load_user_master']().get('users', {}).get(user, {})
            if not device or device != sess.get('device_id') or not _eligible(g, h, user, row, device):
                h.send_json({'ok': False, 'error': 'This device cannot be remembered.'}, 403); return True
            for key, rec in list(data.items()):
                if rec['user'] == user and (rec['device_id'] == device or row.get('single_device')): data.pop(key)
            secret = secrets.token_urlsafe(48)
            data[hashlib.sha256(secret.encode()).hexdigest()] = {'user': user, 'device_id': device, 'device_name': sess.get('device_name', 'Android device'), 'session_id': sess.get('session_id') or secrets.token_hex(8), 'role': row['role'], 'session_version': int(row.get('session_version', 0) or 0), 'password_fingerprint': _fingerprint(g, user, row), 'expires': time.time() + TTL}
            _save(g, data)
            g['append_config_audit']('native_device_remembered', {'device_id': device}, actor=user)
            h.send_json({'ok': True, 'token': secret, 'expires_in': TTL})
    except (ValueError, TypeError, KeyError):
        h.send_json({'ok': False, 'error': 'Unable to verify device login. Please sign in again.'}, 400)
    except OSError:
        h.send_json({'ok': False, 'error': 'Device login storage is unavailable. Try again later.'}, 503)
    return True
