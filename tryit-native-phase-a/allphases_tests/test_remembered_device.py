import copy
import hashlib
import json
import time
import urllib.error
import urllib.request
from test_http_orders_regression import OrdersHttpTests


class RememberedDeviceTests(OrdersHttpTests):
    def setUp(self):
        super().setUp()
        s = self.server
        s.OMS_SESSIONS.clear()
        s._save_user_master({'users': {'admin': {'role': 'admin', 'active': True, 'session_version': 0}, 'viewer': {'role': 'staff', 'active': True, 'password_hash': s._pbkdf2_hash('test-password'), 'session_version': 0, 'permissions': {'orders_view': True}}}})
        for user in ('admin', 'viewer'):
            s.OMS_SESSIONS[user + '-token'] = {'user': user, 'role': 'admin' if user == 'admin' else 'staff', 'session_version': 0, 'expires': time.time() + 3600, 'device_id': 'test', 'session_id': user + '-sid'}
        p = s.SECURITY_DIR / 'native_remembered_devices.json'
        if p.exists(): p.unlink()
        s.LOGIN_ATTEMPTS.clear()

    def post(self, path, body, user=None):
        headers = {'Content-Type': 'application/json'}
        if user: headers['Cookie'] = 'kiran_oms_user=' + user + '-token'
        req = urllib.request.Request(self.base + path, json.dumps(body).encode(), headers)
        try: response = urllib.request.urlopen(req)
        except urllib.error.HTTPError as e: response = e
        with response:
            return response.code, json.loads(response.read()), response.headers.get('Set-Cookie', '')

    def enroll(self, user='viewer'):
        code, body, _ = self.post('/api/mobile/auth/enroll', {'device_id': 'test'}, user)
        self.assertEqual(code, 200, body)
        return {'device_id': 'test', 'token': body['token']}

    def test_enroll_requires_authenticated_matching_device(self):
        self.assertEqual(self.post('/api/mobile/auth/enroll', {'device_id': 'test'})[0], 401)
        self.assertEqual(self.post('/api/mobile/auth/enroll', {'device_id': 'other'}, 'viewer')[0], 403)

    def test_expired_session_renews_without_password_or_business_changes(self):
        secret = self.enroll()
        before = copy.deepcopy(self.server.STATE)
        self.server.OMS_SESSIONS['viewer-token']['expires'] = 0
        self.server._cleanup_oms_sessions()
        code, body, cookie = self.post('/api/mobile/auth/refresh', secret)
        self.assertEqual(code, 200, body); self.assertIn('HttpOnly', cookie); self.assertIn('SameSite=Strict', cookie)
        self.assertEqual(body['profile']['username'], 'viewer'); self.assertEqual(before, self.server.STATE)
        self.assertNotIn('password', body)

    def test_security_file_hashes_token_and_survives_memory_restart(self):
        secret = self.enroll(); raw = (self.server.SECURITY_DIR / 'native_remembered_devices.json').read_text()
        self.assertNotIn(secret['token'], raw); self.assertIn(hashlib.sha256(secret['token'].encode()).hexdigest(), raw)
        self.server.OMS_SESSIONS.clear()
        self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 200)

    def test_wrong_token_device_expiry_and_revocation(self):
        secret = self.enroll()
        self.assertEqual(self.post('/api/mobile/auth/refresh', {**secret, 'device_id': 'other'})[0], 401)
        self.assertEqual(self.post('/api/mobile/auth/refresh', {**secret, 'token': 'x' * 64})[0], 401)
        self.assertEqual(self.post('/api/mobile/auth/revoke', secret)[0], 200)
        self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)
        secret = self.enroll(); p = self.server.SECURITY_DIR / 'native_remembered_devices.json'; records = json.loads(p.read_text())
        for r in records.values(): r['expires'] = 0
        p.write_text(json.dumps(records)); self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)

    def test_force_logout_user_blocks_expired_remembered_device(self):
        secret = self.enroll(); self.server.OMS_SESSIONS.pop('viewer-token')
        self.assertEqual(self.post('/api/admin/sessions/manage', {'action': 'logout_user', 'username': 'viewer'}, 'admin')[0], 200)
        self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)

    def test_force_logout_session_and_all_revoke_credentials(self):
        secret = self.enroll()
        self.assertEqual(self.post('/api/admin/sessions/manage', {'action': 'logout_session', 'session_id': 'viewer-sid'}, 'admin')[0], 200)
        self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)
        self.server.OMS_SESSIONS['viewer-token'] = {'user': 'viewer', 'role': 'staff', 'session_version': 0, 'expires': time.time() + 3600, 'device_id': 'test'}
        secret = self.enroll(); self.server.OMS_SESSIONS.pop('viewer-token')
        self.assertEqual(self.post('/api/admin/sessions/manage', {'action': 'logout_all'}, 'admin')[0], 200)
        self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)

    def test_disable_then_enable_does_not_restore_device_trust(self):
        secret = self.enroll()
        for action in ('deactivate', 'activate'):
            self.assertEqual(self.post('/api/admin/users/manage', {'action': action, 'username': 'viewer'}, 'admin')[0], 200)
        self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)

    def test_password_role_version_and_network_policy_fail_closed(self):
        for field, value in [('password_hash', 'new-hash'), ('role', 'client'), ('session_version', 1), ('allowed_ips', ['192.0.2.55']), ('restrict_devices', True), ('must_change_password', True)]:
            with self.subTest(field=field):
                self.setUp(); secret = self.enroll(); d = self.server._load_user_master(); d['users']['viewer'][field] = value; self.server._save_user_master(d)
                self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)

    def test_admin_device_revoke_and_user_logout_are_final(self):
        secret = self.enroll()
        self.assertEqual(self.post('/api/admin/sessions/manage', {'action': 'revoke_device', 'username': 'viewer', 'device_id': 'test'}, 'admin')[0], 200)
        self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)
        self.setUp(); secret = self.enroll()
        self.assertEqual(self.post('/api/oms/logout', {}, 'viewer')[0], 200)
        self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)

    def test_single_device_fresh_login_revokes_previous_credential(self):
        secret = self.enroll(); d = self.server._load_user_master(); d['users']['viewer']['single_device'] = True; self.server._save_user_master(d)
        code, body, _ = self.post('/api/oms/login', {'username': 'viewer', 'password': 'test-password', 'device_id': 'new-phone'})
        self.assertEqual(code, 200, body); self.assertEqual(self.post('/api/mobile/auth/refresh', secret)[0], 401)

    def test_corrupt_security_file_does_not_create_trust(self):
        p = self.server.SECURITY_DIR / 'native_remembered_devices.json'; p.parent.mkdir(exist_ok=True, parents=True); p.write_text('broken')
        self.assertEqual(self.post('/api/mobile/auth/enroll', {'device_id': 'test'}, 'viewer')[0], 400)
        self.assertEqual(p.read_text(), 'broken')
