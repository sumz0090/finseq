import concurrent.futures
import copy
import json
import time
import urllib.error
import urllib.request

import test_http_orders_regression as http_base


class WorkspaceHttpTests(http_base.OrdersHttpTests):
    def setUp(self):
        super().setUp()
        self.server._save_user_master({'users': {'admin': {'role': 'admin', 'active': True, 'session_version': 0, 'full_name': 'Administrator', 'designation': 'Admin'}, 'viewer': {'role': 'staff', 'active': True, 'session_version': 0, 'full_name': 'Viewer', 'designation': 'Staff', 'permissions': {'orders_view': True}}, 'ops': {'role': 'staff', 'active': True, 'session_version': 0, 'permissions': {'production_view': True}}}})
        self.server.OMS_SESSIONS.clear()
        for user in ('admin', 'viewer', 'ops'):
            self.server.OMS_SESSIONS[user + '-token'] = {'user': user, 'role': 'admin' if user == 'admin' else 'staff', 'session_version': 0, 'expires': time.time() + 3600, 'session_id': user + '-session', 'device_id': 'test'}

    def workspace(self, module, user='admin'): return self.call('/api/mobile/workspace/context?module=' + module, user)
    def mutate(self, body, user='admin'): return self.call('/api/mobile/workspace/save', user, body)
    def request_body(self, action, **fields): return dict(action=action, reset_epoch='http-epoch', request_id='http-all-' + self.server.secrets.token_hex(12), **fields)

    def test_hub_is_authorized_and_does_not_expose_other_module_data(self):
        code, response = self.workspace('hub', 'viewer')
        self.assertEqual(code, 200); self.assertFalse(response['is_admin']); self.assertFalse(response['permissions']['masters_view'])
        self.assertNotIn('orders', response); self.assertNotIn('users', response); self.assertNotIn('masters', response)
        self.assertEqual(self.workspace('hub', None)[0], 401)

    def test_all_module_contexts_for_admin(self):
        for module in ('masters', 'stock', 'production', 'dispatch', 'business', 'reports', 'admin', 'audit'):
            code, response = self.workspace(module)
            self.assertEqual(code, 200, (module, response)); self.assertTrue(response['ok'])

    def test_admin_routes_denied_to_staff(self):
        self.assertEqual(self.workspace('admin', 'viewer')[0], 403)
        code, _ = self.call('/api/mobile/workspace/admin/save', 'viewer', {'route': 'users', 'fields': {'action': 'save', 'username': 'hacker', 'full_name': 'H', 'designation': 'H', 'role': 'admin', 'password': 'test-password'}})
        self.assertEqual(code, 403); self.assertNotIn('hacker', self.server._load_user_master()['users'])

    def test_create_master_live_state_and_disk(self):
        code, response = self.mutate(self.request_body('master_create', category='party', fields={'name': 'NEW PARTY', 'code': 'NEW001'}))
        self.assertEqual(code, 200, response)
        raw = self.call('/api/state')[1]['values']['st_masters']
        self.assertTrue(any(m['name'] == 'NEW PARTY' for m in json.loads(raw)['party']))
        disk = json.loads(self.server.STATE_FILE.read_text('utf-8'))
        self.assertEqual(disk['values']['st_masters'], raw)
        self.server._validate_state_dict(disk)

    def test_concurrent_master_adds_preserve_both(self):
        bodies = [self.request_body('master_create', category='party', fields={'name': name, 'code': code}) for name, code in (('PARTY TWO', 'P2'), ('PARTY THREE', 'P3'))]
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: responses = list(pool.map(self.mutate, bodies))
        self.assertEqual([r[0] for r in responses], [200, 200])
        masters = json.loads(self.server.STATE['values']['st_masters'])
        self.assertEqual(len(masters['party']), 3)

    def test_concurrent_same_profile_stale_edit_refused(self):
        row = self.workspace('masters')[1]['masters']['party'][0]
        bodies = [self.request_body('master_edit', category='party', old_name='PARTY', expected_token=row['token'], fields={'name': 'PARTY', 'code': 'P001', 'phone': str(n)}) for n in (1, 2)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: responses = list(pool.map(self.mutate, bodies))
        self.assertEqual(sorted(r[0] for r in responses), [200, 409])

    def test_admin_create_edit_permissions_and_stale_token(self):
        fields = {'action': 'save', 'username': 'employee', 'full_name': 'New Employee', 'designation': 'Operator', 'password': 'qa-password-123', 'role': 'staff', 'permissions': {'stock_view': True}, 'active': True}
        code, result = self.call('/api/mobile/workspace/admin/save', body={'route': 'users', 'fields': fields, 'expected_token': ''})
        self.assertEqual(code, 200, result)
        ctx = self.workspace('admin')[1]
        row = next(w for w in ctx['users'] if w['data']['username'] == 'employee')
        self.assertNotIn('password_hash', row['data']); self.assertNotIn('qa-password-123', json.dumps(ctx))
        payload = {'route': 'users', 'expected_token': row['token'], 'fields': dict(row['data'], action='save', designation='Manager', permissions={'stock_view': True, 'stock_edit': True})}
        self.assertEqual(self.call('/api/mobile/workspace/admin/save', body=payload)[0], 200)
        self.assertEqual(self.call('/api/mobile/workspace/admin/save', body=payload)[0], 409)
        user = self.server._load_user_master()['users']['employee']; self.assertEqual(user['designation'], 'Manager'); self.assertTrue(user['permissions']['stock_edit'])

    def test_primary_admin_disable_and_self_demotion_refused(self):
        row = next(w for w in self.workspace('admin')[1]['users'] if w['data']['username'] == 'admin')
        for extra in ({'active': False}, {'role': 'staff'}):
            fields = dict(row['data'], action='save', **extra)
            self.assertEqual(self.call('/api/mobile/workspace/admin/save', body={'route': 'users', 'expected_token': row['token'], 'fields': fields})[0], 400)
        self.assertEqual(self.call('/api/state')[0], 200)

    def test_role_change_revokes_existing_admin_authority(self):
        d = self.server._load_user_master(); d['users']['viewer']['role'] = 'admin'; self.server._save_user_master(d)
        self.server.OMS_SESSIONS['viewer-token']['role'] = 'admin'
        self.assertEqual(self.workspace('admin', 'viewer')[0], 200)
        d['users']['viewer']['role'] = 'staff'; self.server._save_user_master(d)
        self.assertEqual(self.workspace('admin', 'viewer')[0], 401)

    def test_session_logout_keeps_current_and_removes_target(self):
        fields = {'action': 'logout_session', 'session_id': 'viewer-session'}
        self.assertEqual(self.call('/api/mobile/workspace/admin/save', body={'route': 'sessions', 'fields': fields})[0], 200)
        self.assertEqual(self.call('/api/state', 'viewer')[0], 401)
        self.assertEqual(self.call('/api/state')[0], 200)

    def test_device_approval_then_revocation(self):
        d = self.server._load_user_master(); d['users']['viewer']['pending_devices'] = [{'id': 'qa-device', 'name': 'QA Phone'}]; self.server._save_user_master(d)
        for action in ('approve_device', 'revoke_device'):
            fields = {'action': action, 'username': 'viewer', 'device_id': 'qa-device'}
            code, result = self.call('/api/mobile/workspace/admin/save', body={'route': 'sessions', 'fields': fields})
            self.assertEqual(code, 200, result)
            row = self.server._load_user_master()['users']['viewer']
            self.assertEqual(len(row['approved_devices']), 1 if action == 'approve_device' else 0)

    def test_expired_session_cannot_read_or_write(self):
        self.server.OMS_SESSIONS['viewer-token']['expires'] = time.time() - 1
        self.assertEqual(self.workspace('hub', 'viewer')[0], 401)
        self.assertEqual(self.mutate(self.request_body('master_create'), 'viewer')[0], 401)

    def test_production_view_can_open_native_order_details_without_orders_view(self):
        self.call('/api/mobile/orders/save', body=self.body_create())
        code, context = self.call('/api/mobile/orders/context', 'ops')
        self.assertEqual(code, 200); self.assertEqual(len(context['orders']), 1)
        self.assertFalse(context['permissions']['orders_edit']); self.assertEqual(context['masters']['party'], [])

    def body_create(self):
        return copy.deepcopy(self.body_original)

    @property
    def body_original(self):
        return {'action': 'create', 'reset_epoch': 'http-epoch', 'request_id': 'http-order-qa-00001', 'lines': [{'party': 'PARTY', 'factory': 'FACTORY', 'article': 'A1', 'colour': 'BLACK', 'box': 'BOX', 'orderDate': '10-10-2026', 'sizes': {'sz6': 12}}]}

    def raw_export(self, options='', user='admin'):
        headers = {'Cookie': 'kiran_oms_user=' + user + '-token'} if user else {}
        r = urllib.request.Request(self.base + '/api/mobile/workspace/export?module=reports' + options, headers=headers)
        try:
            with urllib.request.urlopen(r) as response: return response.status, response.read()
        except urllib.error.HTTPError as error: return error.code, error.read()

    def test_report_export_enforced_and_matches_filters(self):
        self.call('/api/mobile/orders/save', body=self.body_original)
        code, data = self.raw_export('&party=PARTY&from=2026-10-10&to=2026-10-10')
        self.assertEqual(code, 200); self.assertIn(b'PARTY', data); self.assertIn(b'totalPairs', data)
        self.assertEqual(self.raw_export(user='viewer')[0], 403)
        self.assertEqual(self.raw_export('&from=bad-date')[0], 400)

    def test_csv_formula_injection_escaped(self):
        orders = [{'orderNo': 1, 'party': '=SUM(1,2)', 'article': '@cmd', 'orderDate': '10-10-2026'}]
        self.server.STATE['values']['st_orders'] = json.dumps(orders)
        code, data = self.raw_export()
        self.assertEqual(code, 200); self.assertIn(b"'=SUM", data); self.assertIn(b"'@cmd", data)
