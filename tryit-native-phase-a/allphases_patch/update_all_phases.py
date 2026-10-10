#!/usr/bin/env python3
"""TRYIT All Phases API patch. Only the three manifest files may be replaced."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

PACKAGE = Path(__file__).resolve().parent
FILES = ('server.py', 'mobile_orders_api.py', 'mobile_workspace_api.py')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate(root, package=PACKAGE):
    root = Path(root).resolve()
    manifest = json.loads((package / 'manifest.json').read_text('utf-8'))
    if set(manifest['files']) != set(FILES):
        raise ValueError('Invalid patch manifest.')
    for name in FILES:
        source = package / 'payload' / name
        if source.is_symlink() or digest(source) != manifest['files'][name]:
            raise ValueError('Patch payload verification failed: ' + name)
        target = root / name
        if target.is_symlink():
            raise ValueError('Linked application files are unsupported: ' + name)
    current = digest(root / 'server.py')
    if current == manifest['files']['server.py']:
        for name in FILES:
            if not (root / name).exists() or digest(root / name) != manifest['files'][name]:
                raise ValueError('Existing All Phases installation differs or is incomplete; overwrite refused.')
        return manifest, True
    base = next((b for b in manifest['compatible_bases'] if b['server_sha256'] == current), None)
    if base is None:
        raise ValueError('Incompatible server version. No files changed. Supported: Phase 10.37/10.38 or Native Phase C.')
    for name in FILES[1:]:
        expected = base['existing_addons'].get(name)
        target = root / name
        if expected is None and target.exists():
            raise ValueError('Unexpected existing addon; automatic overwrite refused: ' + name)
        if expected is not None and (not target.exists() or digest(target) != expected):
            raise ValueError('Installed addon differs from supported Phase C: ' + name)
    return manifest, False



def atomic_copy(source, target):
    temporary = target.with_name(target.name + '.nativeall-' + uuid.uuid4().hex + '.tmp')
    try:
        shutil.copy2(source, temporary)
        os.replace(temporary, target)
    finally:
        if temporary.exists():
            temporary.unlink()


def backup(root, manifest):
    location = root / 'update_safety_backups' / ('NativeAll_' + time.strftime('%Y%m%d_%H%M%S') + '_' + uuid.uuid4().hex[:8])
    location.mkdir(parents=True, exist_ok=False)
    record = {'app_folder': str(root.resolve()), 'files': {}, 'patch_hashes': manifest['files']}
    for name in FILES:
        target = root / name
        record['files'][name] = digest(target) if target.exists() else None
        if target.exists():
            shutil.copy2(target, location / name)
    (location / 'backup.json').write_text(json.dumps(record, indent=2), 'utf-8')
    return location


def restore(root, location):
    record = json.loads((location / 'backup.json').read_text('utf-8'))
    if Path(record['app_folder']).resolve() != root.resolve() or set(record['files']) != set(FILES):
        raise ValueError('Backup does not match this application.')
    # Validate all backup bytes before changing any file.
    for name, expected in record['files'].items():
        if expected is not None and digest(location / name) != expected:
            raise ValueError('Damaged safety backup: ' + name)
        target = root / name
        if target.is_symlink():
            raise ValueError('Linked target cannot be restored.')
        if target.exists() and digest(target) not in (expected, record['patch_hashes'][name]):
            raise ValueError('Target changed since this patch; rollback refused: ' + name)
    for name, expected in record['files'].items():
        if expected is None:
            target = root / name
            if target.exists():
                target.unlink()
        else:
            atomic_copy(location / name, root / name)


def apply_files(root, manifest, location, package=PACKAGE):
    try:
        # Recheck after stopping the server, before the first replacement.
        validate(root, package)
        for name in FILES:
            atomic_copy(package / 'payload' / name, root / name)
        for name in FILES:
            if digest(root / name) != manifest['files'][name]:
                raise ValueError('Installed file verification failed: ' + name)
    except Exception:
        restore(root, location)
        raise


def windows_cwd(pid):
    """Read an existing process's current directory; never modify its memory."""
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    nt = ctypes.WinDLL('ntdll')
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.ReadProcessMemory.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    nt.NtQueryInformationProcess.argtypes = [wintypes.HANDLE, wintypes.ULONG, ctypes.c_void_p, wintypes.ULONG, ctypes.c_void_p]
    h = kernel.OpenProcess(0x410, False, pid)
    if not h:
        return None
    try:
        def read(address, size):
            buf = ctypes.create_string_buffer(size)
            got = ctypes.c_size_t()
            if not kernel.ReadProcessMemory(h, ctypes.c_void_p(address), buf, size, ctypes.byref(got)) or got.value != size:
                raise OSError('Process directory unavailable.')
            return buf.raw
        wow = ctypes.c_size_t()
        nt.NtQueryInformationProcess(h, 26, ctypes.byref(wow), ctypes.sizeof(wow), None)
        bits = 32 if wow.value else ctypes.sizeof(ctypes.c_void_p) * 8
        if wow.value:
            peb = wow.value
        else:
            basic = (ctypes.c_size_t * 6)()
            if nt.NtQueryInformationProcess(h, 0, basic, ctypes.sizeof(basic), None):
                return None
            peb = basic[1]
        pointer_size = bits // 8
        params = int.from_bytes(read(peb + (0x20 if bits == 64 else 0x10), pointer_size), 'little')
        unicode_string = read(params + (0x38 if bits == 64 else 0x24), 16 if bits == 64 else 8)
        length = struct.unpack_from('<H', unicode_string)[0]
        address = int.from_bytes(unicode_string[8:16] if bits == 64 else unicode_string[4:8], 'little')
        if not 0 < length <= 32768:
            return None
        return read(address, length).decode('utf-16-le')
    except (OSError, ValueError, UnicodeError):
        return None
    finally:
        kernel.CloseHandle(h)


def argv_windows(command):
    from ctypes import wintypes
    shell = ctypes.WinDLL('shell32')
    shell.CommandLineToArgvW.argtypes = [wintypes.LPCWSTR, ctypes.POINTER(ctypes.c_int)]
    shell.CommandLineToArgvW.restype = ctypes.POINTER(wintypes.LPWSTR)
    n = ctypes.c_int()
    args = shell.CommandLineToArgvW(command, ctypes.byref(n))
    if not args:
        return []
    try:
        return [args[i] for i in range(n.value)]
    finally:
        kernel = ctypes.WinDLL('kernel32')
        kernel.LocalFree.argtypes = [ctypes.c_void_p]
        kernel.LocalFree(ctypes.cast(args, ctypes.c_void_p))


def processes():
    script = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); @(Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^(python|pythonw)([0-9.]*)?\\.exe$'} | Select-Object ProcessId,CommandLine,ExecutablePath) | ConvertTo-Json -Compress"
    raw = subprocess.check_output(['powershell.exe', '-NoProfile', '-Command', script], creationflags=0x08000000, timeout=20)
    rows = json.loads(raw.decode('utf-8-sig').strip() or '[]')
    if isinstance(rows, dict):
        rows = [rows]
    result = []
    for row in rows:
        command = row.get('CommandLine') or ''
        args = argv_windows(command)
        script_path = next((a for a in args[1:] if Path(a).name.lower() == 'server.py'), None)
        if not script_path:
            continue
        cwd = windows_cwd(row['ProcessId'])
        if Path(script_path).is_absolute():
            root = Path(script_path).parent.resolve()
        elif cwd:
            root = (Path(cwd) / script_path).resolve().parent
        else:
            root = None
        port = 8787
        for i, arg in enumerate(args):
            if arg == '--port' and i + 1 < len(args):
                port = int(args[i + 1])
            elif arg.startswith('--port='):
                port = int(arg.split('=', 1)[1])
        result.append({'pid': row['ProcessId'], 'root': root, 'command': command, 'cwd': cwd, 'port': port})
    return result


def locate(rows, explicit=None):
    if explicit:
        return Path(explicit).resolve()
    running = {r['root'] for r in rows if r['root'] and (r['root'] / 'server.py').is_file()}
    compatible = set()
    def add(path):
        if not path.is_dir() or not (path / 'server.py').is_file():
            return
        try:
            validate(path)
            compatible.add(path.resolve())
        except (ValueError, OSError):
            pass
    for path in running:
        add(path)
    if len(compatible) == 1:
        return compatible.pop()
    for base in (PACKAGE, PACKAGE.parent, Path.cwd(), Path.home() / 'Desktop', Path.home() / 'Documents', Path('D:/KIRAN DATA'), Path('C:/KIRAN_OMS'), Path('D:/KIRAN_OMS')):
        add(base)
        add(base / 'KIRAN_OMS_App')
        if base.is_dir():
            for parent, dirs, files in os.walk(base):
                depth = len(Path(parent).relative_to(base).parts)
                dirs[:] = [d for d in dirs if not d.startswith('.') and d not in ('data', 'backups', 'update_safety_backups')]
                if depth >= 3:
                    dirs[:] = []
                if 'server.py' in files:
                    add(Path(parent))
    if len(compatible) == 1:
        return compatible.pop()
    import tkinter as tk
    from tkinter import filedialog
    window = tk.Tk(); window.withdraw()
    folder = filedialog.askdirectory(title='Select installed KIRAN_OMS_App folder (contains server.py)')
    window.destroy()
    if not folder:
        raise ValueError('No application folder selected. No files changed.')
    return Path(folder).resolve()


def stop(rows):
    for row in rows:
        subprocess.run(['taskkill.exe', '/PID', str(row['pid']), '/F'], check=True, capture_output=True)
    # Wait until those exact processes are gone; no process-name based kill.
    deadline = time.monotonic() + 12
    ids = {r['pid'] for r in rows}
    while ids and time.monotonic() < deadline:
        alive = {r['pid'] for r in processes()}
        ids &= alive
        if ids:
            time.sleep(.3)
    if ids:
        raise ValueError('Server did not stop; application files were not changed.')


def start(root, previous):
    command = previous['command'] if previous else [sys.executable, str(root / 'server.py'), '--no-open']
    return subprocess.Popen(command, cwd=str(root), creationflags=subprocess.CREATE_NEW_CONSOLE)


def probe(port, expect_patch):
    base = 'http://127.0.0.1:' + str(port)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(base + '/api/health', timeout=2) as response:
        health = json.loads(response.read())
        if not health.get('ok'):
            raise ValueError('Server health check failed.')
    if expect_patch:
        try:
            opener.open(base + '/api/mobile/orders/context', timeout=2)
            raise ValueError('Orders endpoint unexpectedly allowed unauthenticated access.')
        except urllib.error.HTTPError as e:
            if e.code != 401:
                raise ValueError('Orders API was not loaded.') from e
            response = json.loads(e.read())
            if response.get('ok') is not False:
                raise ValueError('Orders endpoint verification failed.')
        try:
            opener.open(base + '/api/mobile/workspace/context?module=hub', timeout=2)
            raise ValueError('Native workspace unexpectedly allowed unauthenticated access.')
        except urllib.error.HTTPError as e:
            if e.code != 401 or json.loads(e.read()).get('ok') is not False:
                raise ValueError('Native workspace API was not loaded.') from e


def wait_health(process, port, patch):
    deadline = time.monotonic() + 25
    last = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ValueError('Restarted server exited; check its console.')
        try:
            probe(port, patch)
            return
        except Exception as exc:
            last = exc
            time.sleep(.6)
    raise ValueError('Server restart health check failed: ' + str(last))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-folder')
    parser.add_argument('--rollback', action='store_true')
    args = parser.parse_args()
    if os.name != 'nt':
        raise ValueError('Run the included BAT on your Windows OMS server PC.')
    rows = processes()
    root = locate(rows, args.app_folder)
    print('Installed application:', root)
    manifest, installed = validate(root)
    if installed and not args.rollback:
        print('All Phases API is already installed. No files changed.')
        return
    matched = [r for r in rows if r['root'] == root]
    if len(matched) > 1:
        raise ValueError('Multiple servers use this app folder. Close them before retrying.')
    if any(r['root'] is None for r in rows):
        raise ValueError('An OMS server process could not be identified safely. Close its server window, then retry.')
    previous = matched[0] if matched else None
    port = previous['port'] if previous else 8787
    if not previous:
        with socket.socket() as s:
            if s.connect_ex(('127.0.0.1', port)) == 0:
                raise ValueError('Port is occupied by another process. No files changed.')
    if args.rollback:
        candidates = sorted((root / 'update_safety_backups').glob('NativeAll_*/backup.json'))
        if not candidates:
            raise ValueError('No All Phases safety backup found.')
        location = candidates[-1].parent
    else:
        location = backup(root, manifest)
    print('Safety backup:', location)
    (location / 'restart.json').write_text(json.dumps({'port': port, 'command': previous['command'] if previous else None}), 'utf-8')
    stop(matched)
    active = None
    try:
        if args.rollback:
            restore(root, location)
        else:
            apply_files(root, manifest, location)
        active = start(root, previous)
        wait_health(active, port, not args.rollback)
    except Exception:
        # Stop only the server launched by this updater, before restoring bytes.
        if active is not None and active.poll() is None:
            active.terminate(); active.wait(timeout=12)
        if not args.rollback:
            restore(root, location)
            original = start(root, previous)
            wait_health(original, port, False)
            print('Original server restored and restarted.')
        raise
    print('SUCCESS: ' + ('Original server restored.' if args.rollback else 'Native D-H APIs installed; server restarted.'))
    print('Refresh existing web clients. Install All Phases APK over Phase B on Android.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('\nUPDATE FAILED:', error)
        sys.exit(1)
