import copy
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request


class OrdersHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        payload = Path(__file__).resolve().parents[1] / 'phasec_patch/payload'
        for name in ('server.py', 'mobile_orders_api.py'):
            shutil.copy2(payload / name, cls.root / name)
        sys.path.insert(0, str(cls.root))
        spec = importlib.util.spec_from_file_location('phasec_test_server', cls.root / 'server.py')
        cls.server = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.server)
        cls.server._save_user_master({'users': {'admin': {'role': 'admin', 'active': True, 'session_version': 0}, 'viewer': {'role': 'staff', 'active': True, 'session_version': 0, 'permissions': {'orders_view': True}}}})
        for user in ('admin', 'viewer'):
            cls.server.OMS_SESSIONS[user + '-token'] = {'user': user, 'role': 'admin' if user == 'admin' else 'staff', 'session_version': 0, 'expires': time.time() + 3600, 'device_id': 'test'}
        cls.server.Handler.log_message = lambda *a: None
        cls.http = cls.server.ThreadingHTTPServer(('127.0.0.1', 0), cls.server.Handler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.http.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown(); cls.http.server_close(); cls.thread.join()
        sys.path.remove(str(cls.root))
        cls.temp.cleanup()

    def setUp(self):
        s = self.server.empty_state()
        masters = {k: [{'name': v, 'code': 'P001' if k == 'party' else ''}] for k, v in {'party': 'PARTY', 'factory': 'FACTORY', 'art': 'A1', 'colour': 'BLACK', 'box': 'BOX'}.items()}
        s['values'] = {'st_masters': json.dumps(masters), 'st_orders': '[]'}
        s['reset_epoch'] = 'http-epoch'
        self.server.STATE = s
        self.body = {'action': 'create', 'reset_epoch': 'http-epoch', 'request_id': 'http-request-000000001', 'lines': [{'party': 'PARTY', 'factory': 'FACTORY', 'article': 'A1', 'colour': 'BLACK', 'box': 'BOX', 'orderDate': '10-10-2026', 'sizes': {'sz6': 12}}]}

    def call(self, path, user='admin', body=None):
        headers = {'Content-Type': 'application/json'}
        if user:
            headers['Cookie'] = 'kiran_oms_user=' + user + '-token'
        r = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            with urllib.request.urlopen(r) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_anonymous_orders_context_denied(self):
        code, data = self.call('/api/mobile/orders/context', user=None)
        self.assertEqual(code, 401)

    def test_viewer_context_has_no_edit_master_access(self):
        code, data = self.call('/api/mobile/orders/context', user='viewer')
        self.assertEqual(code, 200)
        self.assertTrue(data['permissions']['orders_view'])
        self.assertFalse(data['permissions']['orders_add'])
        self.assertTrue(all(not choices for choices in data['masters'].values()))

    def test_save_visible_in_existing_web_state_and_on_disk(self):
        code, data = self.call('/api/mobile/orders/save', body=self.body)
        self.assertEqual(code, 200, data)
        code, state = self.call('/api/state')
        self.assertEqual(code, 200)
        rows = json.loads(state['values']['st_orders'])
        self.assertEqual(rows[0]['totalPairs'], 12)
        self.assertEqual(rows[0]['groupLineNo'], 1)
        disk = json.loads(self.server.STATE_FILE.read_text())
        self.assertEqual(disk['values']['st_orders'], state['values']['st_orders'])
        self.assertEqual(len(json.loads(state['values']['st_audit_log'])), 1)

    def test_viewer_post_cannot_mutate_live_state(self):
        before = copy.deepcopy(self.server.STATE)
        code, data = self.call('/api/mobile/orders/save', user='viewer', body=self.body)
        self.assertEqual(code, 403)
        self.assertEqual(self.server.STATE, before)

    def test_http_stale_edit_conflict(self):
        code, created = self.call('/api/mobile/orders/save', body=self.body)
        wrapper = created['orders'][0]
        edit = {'action': 'edit', 'reset_epoch': 'http-epoch', 'order_no': wrapper['data']['orderNo'], 'expected_token': wrapper['token'], 'fields': {**self.body['lines'][0], 'particular': 'edited'}}
        self.assertEqual(self.call('/api/mobile/orders/save', body=edit)[0], 200)
        code, data = self.call('/api/mobile/orders/save', body=edit)
        self.assertEqual(code, 409)
        self.assertIn('another device', data['error'])

    def test_unrelated_existing_api_routes_still_work(self):
        self.assertEqual(self.call('/api/health')[0], 200)
        code, sess = self.call('/api/oms/session')
        self.assertEqual(code, 200)
        self.assertTrue(sess['authenticated'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
