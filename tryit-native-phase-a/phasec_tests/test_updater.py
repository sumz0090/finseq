import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

BASE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('phasec_updater', BASE / 'phasec_patch/update_phase_c.py')
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)


class UpdaterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'KIRAN_OMS_App'
        self.root.mkdir()
        shutil.copy2(BASE / 'backend/extracted/KIRAN_OMS_App/server.py', self.root / 'server.py')
        for name in ('index.html', 'server_config.json', 'data/intranet_state.json', 'data/security/user_master.json', 'data/backups/existing.json'):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(('PRIVATE LIVE ' + name).encode())
        self.original = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}

    def tearDown(self):
        self.temp.cleanup()

    def assert_preserved(self, restored=False):
        for name, content in self.original.items():
            if str(name) != 'server.py' or restored:
                self.assertEqual((self.root / name).read_bytes(), content)

    def test_apply_preserves_state_users_config_web_and_backups(self):
        manifest, installed = u.validate(self.root)
        self.assertFalse(installed)
        location = u.backup(self.root, manifest)
        u.apply_files(self.root, manifest, location)
        self.assertTrue(u.validate(self.root)[1])
        self.assert_preserved()
        self.assertEqual((location / 'server.py').read_bytes(), self.original[Path('server.py')])

    def test_rollback_restores_base_and_removes_only_new_module(self):
        manifest, _ = u.validate(self.root)
        location = u.backup(self.root, manifest)
        u.apply_files(self.root, manifest, location)
        u.restore(self.root, location)
        self.assert_preserved(restored=True)
        self.assertFalse((self.root / 'mobile_orders_api.py').exists())

    def test_partial_apply_failure_automatically_restores(self):
        manifest, _ = u.validate(self.root)
        location = u.backup(self.root, manifest)
        actual_copy = u.atomic_copy
        count = 0
        def fail_second(source, target):
            nonlocal count
            count += 1
            if count == 2:
                raise OSError('disk failure')
            return actual_copy(source, target)
        with patch.object(u, 'atomic_copy', side_effect=fail_second):
            with self.assertRaises(OSError):
                u.apply_files(self.root, manifest, location)
        self.assert_preserved(restored=True)
        self.assertFalse((self.root / 'mobile_orders_api.py').exists())

    def test_wrong_base_refused_without_changes(self):
        (self.root / 'server.py').write_text('different server')
        with self.assertRaises(ValueError):
            u.validate(self.root)
        self.assert_preserved()

    def test_payload_tampering_refused(self):
        package = Path(self.temp.name) / 'patch'
        shutil.copytree(BASE / 'phasec_patch', package)
        (package / 'payload/mobile_orders_api.py').write_text('tampered')
        with self.assertRaises(ValueError):
            u.validate(self.root, package)
        self.assert_preserved(restored=True)

    def test_backup_tampering_refused_before_restore(self):
        manifest, _ = u.validate(self.root)
        location = u.backup(self.root, manifest)
        u.apply_files(self.root, manifest, location)
        (location / 'server.py').write_text('damaged')
        with self.assertRaises(ValueError):
            u.restore(self.root, location)
        self.assertTrue(u.validate(self.root)[1])

    def test_later_server_edit_not_overwritten_by_rollback(self):
        manifest, _ = u.validate(self.root)
        location = u.backup(self.root, manifest)
        u.apply_files(self.root, manifest, location)
        (self.root / 'server.py').write_text('newer server')
        with self.assertRaises(ValueError):
            u.restore(self.root, location)
        self.assertEqual((self.root / 'server.py').read_text(), 'newer server')

    def test_preexisting_unrecognized_addon_refused(self):
        (self.root / 'mobile_orders_api.py').write_text('different addon')
        with self.assertRaises(ValueError):
            u.validate(self.root)
        self.assert_preserved(restored=True)

    def test_backup_for_different_folder_refused(self):
        manifest, _ = u.validate(self.root)
        location = u.backup(self.root, manifest)
        record = json.loads((location / 'backup.json').read_text())
        record['app_folder'] = str(Path(self.temp.name) / 'another_app')
        (location / 'backup.json').write_text(json.dumps(record))
        with self.assertRaises(ValueError):
            u.restore(self.root, location)
        self.assert_preserved(restored=True)

    def test_process_parser_on_windows(self):
        if u.os.name != 'nt':
            self.skipTest('Windows-only native process inspection')
        import subprocess
        import sys
        import time
        # Fixture process has the same relative invocation as START_KIRAN_OMS.bat.
        script = self.root / 'server.py'
        script.write_text('import time;time.sleep(30)')
        child = subprocess.Popen([sys.executable, 'server.py', '--port', '18787'], cwd=self.root)
        try:
            time.sleep(.8)
            matches = [r for r in u.processes() if r['pid'] == child.pid]
            self.assertEqual(len(matches), 1)
            self.assertEqual(matches[0]['root'], self.root.resolve())
            self.assertEqual(matches[0]['port'], 18787)
            u.stop(matches)
            child.wait(timeout=5)
        finally:
            if child.poll() is None:
                child.terminate(); child.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()

class WindowsRestartTests(unittest.TestCase):
    @unittest.skipUnless(u.os.name == 'nt', 'Windows-only complete updater restart check')
    def test_real_server_apply_restart_and_rollback(self):
        import socket
        import subprocess
        import sys
        import time
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'KIRAN_OMS_App'
            root.mkdir()
            shutil.copy2(BASE / 'backend/extracted/KIRAN_OMS_App/server.py', root / 'server.py')
            with socket.socket() as s:
                s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
            original = subprocess.Popen([sys.executable, str(root / 'server.py'), '--no-open', '--port', str(port)], cwd=root)
            try:
                u.wait_health(original, port, False)
                with patch.object(sys, 'argv', ['update_phase_c.py', '--app-folder', str(root)]):
                    u.main()
                original.wait(timeout=10)
                self.assertTrue(u.validate(root)[1])
                u.probe(port, True)
                with patch.object(sys, 'argv', ['update_phase_c.py', '--app-folder', str(root), '--rollback']):
                    u.main()
                self.assertFalse(u.validate(root)[1])
                self.assertFalse((root / 'mobile_orders_api.py').exists())
                u.probe(port, False)
            finally:
                if original.poll() is None:
                    original.terminate(); original.wait(timeout=10)
                u.stop([r for r in u.processes() if r['root'] == root.resolve()])
                time.sleep(.3)
