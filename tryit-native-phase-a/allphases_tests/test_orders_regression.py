import copy
import importlib.util
import json
import pathlib
import sys
import unittest

API_PATH = pathlib.Path(__file__).resolve().parents[1] / 'allphases_patch/payload/mobile_orders_api.py'
spec = importlib.util.spec_from_file_location('mobile_orders_api', API_PATH)
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class NativeOrdersTests(unittest.TestCase):
    def setUp(self):
        self.saved = 0
        self.state = {'version': 1, 'updated_at': '', 'values': {}, 'key_versions': {}, 'key_clients': {}, 'reset_epoch': 'epoch-1'}
        masters = {k: [{'name': v, 'code': 'P001' if k == 'party' else ''}] for k, v in {'party': 'PARTY', 'factory': 'FACTORY', 'art': 'A1', 'colour': 'BLACK', 'box': 'BOX', 'sole': 'SOLE', 'dml': 'D', 'material': 'LEATHER'}.items()}
        self.state['values']['st_masters'] = json.dumps(masters)
        self.state['values']['st_orders'] = '[]'
        self.g = {'STATE': self.state, '_safe_json': lambda raw, default: json.loads(raw) if isinstance(raw, str) else (raw if raw is not None else default), '_has_perm': lambda sess, p: sess.get('role') == 'admin' or p in sess.get('permissions', []), 'now_iso': lambda: '2026-10-10T12:00:00+00:00', 'atomic_save_state': self.save}
        self.admin = {'user': 'admin', 'role': 'admin', 'device_id': 'test-phone'}
        self.fields = {'party': 'PARTY', 'factory': 'FACTORY', 'article': 'A1', 'colour': 'BLACK', 'box': 'BOX', 'sizes': {'sz6': 12}, 'orderDate': '10-10-2026'}

    def save(self):
        self.saved += 1

    def create(self, **extra):
        body = {'action': 'create', 'request_id': 'request-0000000001', 'reset_epoch': 'epoch-1', 'lines': [copy.deepcopy(self.fields)], **extra}
        return api.mutate(self.g, self.admin, body)

    def status(self, old, target, **extra):
        return api.mutate(self.g, self.admin, {'action': 'status', 'order_no': old['orderNo'], 'expected_token': api.token(old), 'reset_epoch': 'epoch-1', 'target': target, 'action_date': '10-10-2026', **extra})[0]

    def test_multiline_group_atomic(self):
        fields2 = copy.deepcopy(self.fields); fields2['sizes'] = {'sz7': 24}
        rows = self.create(lines=[self.fields, fields2], allow_duplicate=True)
        self.assertEqual([o['groupLineNo'] for o in rows], [1, 2])
        self.assertEqual(rows[0]['groupOrderNo'], rows[1]['groupOrderNo'])
        self.assertEqual([o['orderNo'] for o in rows], [1, 2])
        self.assertEqual(sum(o['totalPairs'] for o in rows), 36)
        self.assertEqual(self.saved, 1)

    def test_create_retry_is_idempotent(self):
        first = self.create(); again = self.create()
        self.assertEqual(first, again)
        self.assertEqual(self.saved, 1)
        self.assertEqual(len(json.loads(self.state['values']['st_orders'])), 1)

    def test_request_id_cannot_be_reused_for_different_lines(self):
        self.create()
        with self.assertRaises(api.ApiError):
            self.create(lines=[{**self.fields, 'rate': 200}])

    def test_create_permission_enforced(self):
        with self.assertRaises(api.ApiError) as c:
            api.mutate(self.g, {'user': 'viewer', 'permissions': ['orders_view']}, {'action': 'create', 'reset_epoch': 'epoch-1'})
        self.assertEqual(c.exception.code, 403)
        self.assertEqual(self.saved, 0)

    def test_invalid_size_and_date_leave_no_partial_lines(self):
        for bad in ({'sizes': {'sz6': -1}}, {'sizes': {'sz6': 1.5}}, {'sizes': {'sz6': True}}, {'orderDate': '31-02-2026'}, {'rate': float('nan')}):
            with self.subTest(bad=bad), self.assertRaises(api.ApiError):
                self.create(lines=[self.fields, {**self.fields, **bad}])
            self.assertEqual(self.saved, 0)
            self.assertEqual(self.state['values']['st_orders'], '[]')

    def test_master_validation_and_auto_party_code(self):
        row = self.create()[0]
        self.assertEqual(row['partyCode'], 'P001')
        with self.assertRaises(api.ApiError):
            self.create(request_id='request-0000000002', lines=[{**self.fields, 'article': 'UNKNOWN'}])

    def test_duplicate_requires_explicit_confirmation(self):
        self.create()
        with self.assertRaises(api.ApiError) as c:
            self.create(request_id='request-0000000002')
        self.assertTrue(c.exception.details['requires_duplicate_confirmation'])
        self.assertEqual(len(self.create(request_id='request-0000000002', allow_duplicate=True)), 1)

    def test_stale_edit_rejected_without_overwrite(self):
        old = self.create()[0]
        first = api.mutate(self.g, self.admin, {'action': 'edit', 'order_no': old['orderNo'], 'expected_token': api.token(old), 'reset_epoch': 'epoch-1', 'fields': {**self.fields, 'particular': 'new comment'}})[0]
        with self.assertRaises(api.ApiError) as c:
            api.mutate(self.g, self.admin, {'action': 'edit', 'order_no': old['orderNo'], 'expected_token': api.token(old), 'reset_epoch': 'epoch-1', 'fields': self.fields})
        self.assertEqual(c.exception.code, 409)
        self.assertEqual(json.loads(self.state['values']['st_orders'])[0]['particular'], first['particular'])

    def test_edit_preserves_group_and_print_metadata(self):
        row = self.create()[0]
        row.update(lastBatch='B001', printed='printed', customLegacyField='retain')
        self.state['values']['st_orders'] = json.dumps([row])
        changed = api.mutate(self.g, self.admin, {'action': 'edit', 'order_no': row['orderNo'], 'expected_token': api.token(row), 'reset_epoch': 'epoch-1', 'fields': {**self.fields, 'rate': 90}})[0]
        for k in ('lastBatch', 'printed', 'groupOrderNo', 'groupLineNo', 'createdAt', 'customLegacyField'):
            self.assertEqual(changed[k], row[k])

    def test_unrelated_orders_survive_targeted_edit(self):
        row = self.create()[0]
        unrelated = {**row, 'orderNo': 500, 'particular': 'concurrent other device'}
        self.state['values']['st_orders'] = json.dumps([row, unrelated])
        api.mutate(self.g, self.admin, {'action': 'edit', 'order_no': row['orderNo'], 'expected_token': api.token(row), 'reset_epoch': 'epoch-1', 'fields': {**self.fields, 'rate': 50}, 'allow_duplicate': True})
        self.assertEqual(json.loads(self.state['values']['st_orders'])[1], unrelated)

    def test_reset_epoch_rejected(self):
        with self.assertRaises(api.ApiError) as c:
            self.create(reset_epoch='old-epoch')
        self.assertEqual(c.exception.code, 409)
        self.assertEqual(self.saved, 0)

    def test_lifecycle_and_direct_dispatch(self):
        row = self.create()[0]
        with self.assertRaises(api.ApiError):
            self.status(row, 'Ready')
        with self.assertRaises(api.ApiError) as c:
            self.status(row, 'In Production')
        self.assertTrue(c.exception.details['requires_batch_confirmation'])
        prod = self.status(row, 'In Production', confirm_without_batch=True)
        sf = json.loads(self.state['values']['sf_orders'])
        self.assertEqual(sf[0]['_stOrderNo'], row['orderNo'])
        dispatch = self.status(prod, 'Dispatched')
        self.assertEqual(dispatch['readyDate'], dispatch['dispatchDate'])
        events = json.loads(self.state['values']['st_status_event_history'])
        self.assertEqual([e['action'] for e in events[:2]], ['Dispatched', 'Ready'])

    def test_status_permissions_and_date_validation(self):
        row = self.create()[0]
        prod = self.status(row, 'In Production', confirm_without_batch=True)
        with self.assertRaises(api.ApiError):
            self.status(prod, 'Ready', action_date='09-10-2026')
        with self.assertRaises(api.ApiError) as c:
            api.mutate(self.g, {'user': 'staff', 'permissions': ['orders_view', 'orders_edit']}, {'action': 'status', 'reset_epoch': 'epoch-1', 'order_no': prod['orderNo'], 'expected_token': api.token(prod), 'target': 'Dispatched'})
        self.assertEqual(c.exception.code, 403)

    def test_hold_preserves_stage_and_blocks_advancement(self):
        row = self.create()[0]
        held = self.status(row, 'Hold')
        self.assertTrue(held['hold']); self.assertEqual(held['status'], 'Pending')
        with self.assertRaises(api.ApiError):
            self.status(held, 'In Production', confirm_without_batch=True)
        resumed = self.status(held, 'Resume')
        self.assertFalse(resumed['hold'])

    def test_atomic_failure_rolls_back_in_memory(self):
        previous = copy.deepcopy(self.state)
        def fail():
            raise OSError('test storage failure')
        self.g['atomic_save_state'] = fail
        with self.assertRaises(OSError):
            self.create()
        self.assertEqual(self.state, previous)


if __name__ == '__main__':
    unittest.main(verbosity=2)
