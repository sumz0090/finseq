import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'allphases_patch/payload'))
import mobile_workspace_api as api


class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.state = {'version': 10, 'values': {}, 'key_versions': {}, 'key_clients': {}, 'updated_at': '', 'reset_epoch': 'epoch-all'}
        self.g = {'STATE': self.state, '_safe_json': lambda raw, default: json.loads(raw) if isinstance(raw, str) else raw if raw is not None else default, '_has_perm': lambda s, p: s.get('role') == 'admin' or p in s.get('permissions', []), 'now_iso': lambda: '2026-10-10T22:00:00+05:30', 'atomic_save_state': lambda: None}
        self.admin = {'user': 'admin', 'role': 'admin'}
        self.masters = {k: [{'name': k.upper(), 'code': 'P01' if k == 'party' else '', 'extra': 'preserve'}] for k in api.CATEGORIES}
        self.put('st_masters', self.masters)
        self.put('st_soles', {'SOLE': {'opening': {'sz6': 20}, 'purchase': {'sz6': 10}, 'purchaseOrdered': {}}})
        self.put('st_boxes', [{'name': 'BOX', 'stock': 20, 'ordered': 5, 'extra': 'keep'}])
        self.orders = [{'orderNo': 1, 'groupOrderNo': 1, 'party': 'PARTY', 'factory': 'FACTORY', 'article': 'ART', 'colour': 'COLOUR', 'sole': 'SOLE', 'box': 'BOX', 'totalPairs': 10, 'sizes': {'sz6': 10}, 'status': 'In Production', 'hold': False, 'orderDate': '10-10-2026', 'printed': 'batch-1'}]
        self.put('st_orders', self.orders)
        self.put('st_facArtSole', {'FACTORY||ART': 'SOLE'})

    def put(self, key, value): self.state['values'][key] = json.dumps(value)
    def get(self, key, default=None): return json.loads(self.state['values'].get(key, json.dumps(default)))
    def body(self, action, **fields): return dict({'action': action, 'reset_epoch': 'epoch-all', 'request_id': 'qa-' + api.uuid.uuid4().hex}, **fields)
    def create_po(self, qty=10):
        body = self.body('po_create', sole='SOLE', party='SOLEVENDOR', sizes={'sz6': qty}, date='10-10-2026', rate=2)
        api.mutate(self.g, self.admin, body)
        return self.get('st_po_log')[0]

    def test_all_nine_master_categories_create(self):
        for category in api.CATEGORIES:
            api.mutate(self.g, self.admin, self.body('master_create', category=category, fields={'name': 'NEW ' + category}))
            self.assertEqual(len(self.get('st_masters')[category]), 2)
        self.assertIn('NEW SOLE', self.get('st_soles'))
        self.assertTrue(any(b['name'] == 'NEW BOX' for b in self.get('st_boxes')))

    def test_party_rating_range_and_profile_preservation(self):
        api.mutate(self.g, self.admin, self.body('master_create', category='party', fields={'name': 'RATED PARTY', 'rating': 3}))
        old = next(m for m in self.get('st_masters')['party'] if m['name'] == 'RATED PARTY')
        api.mutate(self.g, self.admin, self.body('master_edit', category='party', old_name=old['name'], expected_token=api.token(old), fields={'phone': '12345'}))
        self.assertEqual(self.get('st_masters')['party'][-1]['rating'], 3)
        before = copy.deepcopy(self.state)
        with self.assertRaises(api.ApiError):
            api.mutate(self.g, self.admin, self.body('master_create', category='party', fields={'name': 'BAD RATING', 'rating': 4}))
        self.assertEqual(before, self.state)

    def test_duplicate_name_and_party_code_refused(self):
        for fields in ({'name': 'party'}, {'name': 'NEW PARTY', 'code': 'p01'}):
            before = copy.deepcopy(self.state)
            with self.assertRaises(api.ApiError): api.mutate(self.g, self.admin, self.body('master_create', category='party', fields=fields))
            self.assertEqual(before, self.state)

    def test_master_edit_preserves_unknown_fields_and_order_print_metadata(self):
        old = self.masters['party'][0]
        api.mutate(self.g, self.admin, self.body('master_edit', category='party', old_name='PARTY', expected_token=api.token(old), fields={'name': 'RENAMED', 'phone': '9999999999'}))
        self.assertEqual(self.get('st_masters')['party'][0]['extra'], 'preserve')
        row = self.get('st_orders')[0]
        self.assertEqual(row['party'], 'RENAMED'); self.assertEqual(row['printed'], 'batch-1')

    def test_factory_and_article_mapping_rename_is_position_specific(self):
        self.put('st_facArtSole', {'FACTORY||ART': 'SOLE', 'ART||FACTORY': 'OTHER'})
        old = self.masters['art'][0]
        api.mutate(self.g, self.admin, self.body('master_edit', category='art', old_name='ART', expected_token=api.token(old), fields={'name': 'NEW ART'}))
        maps = self.get('st_facArtSole')
        self.assertIn('FACTORY||NEW ART', maps); self.assertIn('ART||FACTORY', maps)

    def test_sole_rename_updates_stock_pos_and_history(self):
        self.create_po()
        self.put('st_purchase_log', [{'id': 'prior', 'sole': 'SOLE', 'sizes': {'sz6': 10}}])
        old = self.masters['sole'][0]
        api.mutate(self.g, self.admin, self.body('master_edit', category='sole', old_name='SOLE', expected_token=api.token(old), fields={'name': 'NEW SOLE'}))
        self.assertNotIn('SOLE', self.get('st_soles')); self.assertIn('NEW SOLE', self.get('st_soles'))
        self.assertEqual(self.get('st_po_log')[0]['sole'], 'NEW SOLE')
        self.assertEqual(self.get('st_purchase_log')[0]['sole'], 'NEW SOLE')
        self.assertEqual(self.get('st_facArtSole')['FACTORY||ART'], 'NEW SOLE')

    def test_master_delete_used_and_stocked_refused(self):
        for category in ('party', 'sole', 'box'):
            old = self.masters[category][0]
            with self.assertRaises(api.ApiError): api.mutate(self.g, self.admin, self.body('master_delete', category=category, old_name=old['name'], expected_token=api.token(old)))

    def test_mapping_only_master_delete_refused_without_changes(self):
        self.put('st_orders', [])
        self.put('st_soles', {'SOLE': {'opening': {}, 'purchase': {}}})
        for category in ('factory', 'art', 'sole'):
            before = copy.deepcopy(self.state)
            with self.assertRaises(api.ApiError):
                old = self.masters[category][0]
                api.mutate(self.g, self.admin, self.body('master_delete', category=category, old_name=old['name'], expected_token=api.token(old)))
            self.assertEqual(before, self.state)

    def test_unused_master_delete_only_selected(self):
        api.mutate(self.g, self.admin, self.body('master_create', category='colour', fields={'name': 'UNUSED'}))
        m = self.get('st_masters')['colour'][1]
        api.mutate(self.g, self.admin, self.body('master_delete', category='colour', old_name='UNUSED', expected_token=api.token(m)))
        self.assertEqual(len(self.get('st_masters')['colour']), 1)

    def test_stale_master_and_epoch_refused(self):
        for extra in ({'expected_token': 'wrong'}, {'expected_token': api.token(self.masters['party'][0]), 'reset_epoch': 'restored'}):
            before = copy.deepcopy(self.state)
            with self.assertRaises(api.ApiError): api.mutate(self.g, self.admin, self.body('master_edit', category='party', old_name='PARTY', fields={'name': 'NEW'}, **extra))
            self.assertEqual(before, self.state)

    def test_permission_denial_for_every_mutation(self):
        staff = {'user': 'viewer', 'role': 'staff', 'permissions': ['orders_view']}
        for action in ('master_create', 'master_edit', 'master_delete', 'sole_edit', 'box_edit', 'po_create', 'po_receive', 'purchase_edit'):
            before = copy.deepcopy(self.state)
            with self.assertRaises(api.ApiError) as result: api.mutate(self.g, staff, self.body(action))
            self.assertEqual(result.exception.code, 403); self.assertEqual(before, self.state)

    def test_each_context_permission_denied(self):
        for module in ('masters', 'stock', 'production', 'dispatch', 'business', 'reports', 'audit'):
            with self.assertRaises(api.ApiError) as result: api.context(self.g, {'user': 'viewer', 'role': 'staff'}, module)
            self.assertEqual(result.exception.code, 403)

    def test_po_create_retry_idempotent_and_unrelated_po_preserved(self):
        body = self.body('po_create', sole='SOLE', party='SOLEVENDOR', sizes={'sz6': 10}, date='10-10-2026')
        one = api.mutate(self.g, self.admin, body)
        two = api.mutate(self.g, self.admin, body)
        self.assertEqual(one, two); self.assertEqual(len(self.get('st_po_log')), 1)
        self.assertEqual(self.get('st_soles')['SOLE']['purchaseOrdered']['sz6'], 10)
        with self.assertRaises(api.ApiError): api.mutate(self.g, self.admin, dict(body, sizes={'sz6': 20}))

    def test_partial_receipt_only_selected_po(self):
        one = self.create_po(10); two = self.create_po(20)
        body = self.body('po_receive', id=one['id'], expected_token=api.token(one), sizes={'sz6': 4}, date='10-10-2026')
        api.mutate(self.g, self.admin, body)
        pos = {e['id']: e for e in self.get('st_po_log')}
        self.assertEqual(pos[one['id']]['receivedQty'], 4); self.assertEqual(pos[two['id']]['receivedQty'], 0)
        self.assertEqual(self.get('st_soles')['SOLE']['purchase']['sz6'], 14)
        self.assertEqual(self.get('st_soles')['SOLE']['purchaseOrdered']['sz6'], 26)
        api.mutate(self.g, self.admin, body)
        self.assertEqual(self.get('st_soles')['SOLE']['purchase']['sz6'], 14)

    def test_over_receipt_explicit_confirmation_extra_opening(self):
        one = self.create_po(10); two = self.create_po(20)
        b = self.body('po_receive', id=one['id'], expected_token=api.token(one), sizes={'sz6': 15}, date='10-10-2026')
        before = copy.deepcopy(self.state)
        with self.assertRaises(api.ApiError) as result: api.mutate(self.g, self.admin, b)
        self.assertTrue(result.exception.details['requires_excess_confirmation']); self.assertEqual(before, self.state)
        api.mutate(self.g, self.admin, dict(b, confirm_excess=True))
        stock = self.get('st_soles')['SOLE']
        self.assertEqual(stock['opening']['sz6'], 25); self.assertEqual(stock['purchase']['sz6'], 20)
        self.assertEqual(stock['purchaseOrdered']['sz6'], 20)
        self.assertEqual({e['dest'] for e in self.get('st_purchase_log')}, {'purchase', 'opening'})

    def test_legacy_scalar_received_qty_allocation(self):
        entry = {'sizes': {'sz4': 4, 'sz5': 6}, 'receivedQty': 7}
        self.assertEqual(api.received_sizes(entry)['sz4'], 4)
        self.assertEqual(api.received_sizes(entry)['sz5'], 3)

    def test_invalid_quantities_rates_and_dates_leave_no_write(self):
        for extra in ({'sizes': {'sz6': -1}}, {'sizes': {'sz6': 2.5}}, {'sizes': {'sz6': True}}, {'rate': float('nan')}, {'date': '31-02-2026'}):
            b = self.body('po_create', sole='SOLE', party='SOLEVENDOR', sizes={'sz6': 10}, date='10-10-2026'); b.update(extra)
            before = copy.deepcopy(self.state)
            with self.assertRaises(api.ApiError): api.mutate(self.g, self.admin, b)
            self.assertEqual(before, self.state)

    def test_receive_date_before_po_refused(self):
        e = self.create_po()
        before = copy.deepcopy(self.state)
        with self.assertRaises(api.ApiError): api.mutate(self.g, self.admin, self.body('po_receive', id=e['id'], expected_token=api.token(e), sizes={'sz6': 2}, date='09-10-2026'))
        self.assertEqual(before, self.state)

    def test_sole_edit_delta_history_and_other_sizes_preserved(self):
        old = self.get('st_soles')['SOLE']
        b = self.body('sole_edit', name='SOLE', kind='purchase', sizes={'sz6': 7}, expected_token=api.token(old), date='10-10-2026')
        api.mutate(self.g, self.admin, b)
        self.assertEqual(self.get('st_purchase_log')[0]['sizes']['sz6'], -3)
        self.assertEqual(self.get('st_soles')['SOLE']['opening']['sz6'], 20)

    def test_box_edit_preserves_other_metadata(self):
        old = self.get('st_boxes')[0]
        api.mutate(self.g, self.admin, self.body('box_edit', name='BOX', expected_token=api.token(old), stock=25, ordered=6))
        b = self.get('st_boxes')[0]
        self.assertEqual(b['extra'], 'keep'); self.assertEqual(b['stock'], 25)

    def test_purchase_edit_does_not_change_quantities(self):
        e = self.create_po()
        api.mutate(self.g, self.admin, self.body('po_receive', id=e['id'], expected_token=api.token(e), sizes={'sz6': 5}, date='10-10-2026'))
        p = self.get('st_purchase_log')[0]
        api.mutate(self.g, self.admin, self.body('purchase_edit', id=p['id'], expected_token=api.token(p), party='OTHER', rate=3, note='Updated'))
        changed = self.get('st_purchase_log')[0]
        self.assertEqual(changed['sizes'], p['sizes']); self.assertEqual(changed['totalQty'], p['totalQty'])

    def test_stock_totals_preserve_consumed_cancelled_and_hold_rules(self):
        self.put('st_orders', self.orders + [dict(self.orders[0], orderNo=2, status='Cancelled', cancelledFromStatus='In Production'), dict(self.orders[0], orderNo=3, hold=True), dict(self.orders[0], orderNo=4, status='Pending')])
        row = api.stock_context(self.g)['soles'][0]['totals']
        self.assertEqual(row['consumed'], 20); self.assertEqual(row['pending'], 10); self.assertEqual(row['balance'], 10)

    def test_party_summary_group_lines_and_hold_counts(self):
        rows = self.orders + [dict(self.orders[0], orderNo=2, hold=True)]
        summary = api.summary(rows)
        self.assertEqual(sum(r['pairs'] for r in summary), 20)
        self.assertEqual({r['name'] for r in summary}, {'In Production', 'Hold'})

    def test_save_failure_rolls_back_memory_and_audit_receipt(self):
        self.g['atomic_save_state'] = lambda: (_ for _ in ()).throw(OSError('disk full'))
        before = copy.deepcopy(self.state)
        with self.assertRaises(OSError): api.mutate(self.g, self.admin, self.body('master_create', category='party', fields={'name': 'NEW'}))
        self.assertEqual(before, self.state)

    def test_filtered_export_rows_match_party_status_group_date(self):
        self.put('st_orders', self.orders + [dict(self.orders[0], orderNo=2, party='OTHER', article='OTHER')])
        filtered = api.filtered_rows(self.g, {'party': 'PARTY', 'status': 'In Production', 'article': 'ART', 'from': '2026-10-10', 'to': '2026-10-10'})
        self.assertEqual(len(filtered), 1)


if __name__ == '__main__': unittest.main()
