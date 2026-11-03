"""Build a local developer bundle; never read device credentials or deploy anything.

The APK and shell worker remain separate processes. Installing an APK cannot
grant shell privileges: the existing Pi bootstrap still requires authorized ADB.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

from build_support import source_files

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
WORKER = ROOT / 'integrations/scrcpy/worker'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def pins():
    values = {}
    tree = ast.parse((ROOT / 'integrations/scrcpy/phone_mirror.py').read_text(encoding='utf-8'))
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in ('VERSION', 'SERVER_SHA256'):
                    values[target.id] = ast.literal_eval(node.value)
    return values['VERSION'], values['SERVER_SHA256']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'output/kigeyes-companion-dev.zip')
    args = parser.parse_args()
    # Build first: packaging arbitrary pre-existing APKs risks mixing old binaries
    # with current sources. Both builders run their own regression checks.
    subprocess.run([sys.executable, str(HERE/'build-windows.py'), '--check-release'], check=True)
    subprocess.run([sys.executable, str(WORKER/'build.py')], check=True)
    version, expected = pins()
    cache = Path.home()/'.cache/kigeyes-scrcpy'
    server = cache/f'scrcpy-server-v{version}'
    if digest(server.read_bytes()) != expected:
        raise RuntimeError('Worker differs from the reviewed Pi pin; update/review the pin deliberately')

    entries = {}
    def add(path, name=None):
        path = Path(path)
        name = name or 'source/'+path.relative_to(ROOT).as_posix()
        if path.is_symlink() or name in entries:
            raise ValueError('Unsafe or duplicate package source: '+name)
        entries[name] = path.read_bytes()

    add(HERE/'app/build/outputs/apk/debug/app-debug.apk', 'bin/KigEyes-debug.apk')
    add(server, 'bin/'+server.name)
    add(cache/'build-manifest.json', 'worker-build-manifest.json')
    for path, _ in source_files(HERE):
        add(path)
    for path in sorted(WORKER.rglob('*')):
        if path.is_file() and (path.suffix in ('.java', '.py', '.patch', '.md') or path.name == 'LICENSE'):
            add(path)
    for path in sorted((ROOT/'integrations/scrcpy').glob('*.py')):
        add(path)
    for name in ('integrations/__init__.py', 'integrations/scrcpy/module.json',
                 'integrations/scrcpy/PHONE_MIRROR.md', 'visor/run.py',
                 'visor/aux_settings.py', 'visor/kigeyes_runtime/__init__.py',
                 'visor/kigeyes_runtime/modules.py', 'visor/dashboard/__init__.py',
                 'visor/dashboard/ui/__init__.py', 'visor/dashboard/ui/phone_touch.py'):
        add(ROOT/name)
    # Include canonical icon inputs so the packaged app sources remain rebuildable.
    for path in sorted((ROOT/'visor/dashboard/icons').iterdir()):
        if path.is_file() and (path.suffix == '.svg' or path.name == 'sources.json' or 'LICENSE' in path.name):
            add(path)
    add(ROOT/'LICENSE')
    add(ROOT/'licenses/GPL-3.txt')
    add(HERE/'DISTRIBUTION.md', 'README.md')
    manifest = dict(kind='developer-preview', worker_version=version,
                    files={name: dict(bytes=len(data), sha256=digest(data)) for name, data in sorted(entries.items())})
    entries['manifest.json'] = (json.dumps(manifest, indent=2)+'\n').encode()
    output = args.output.resolve()
    if output.is_relative_to(HERE) or output.is_relative_to(WORKER):
        raise ValueError('Write bundles outside source directories')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.partial')
    with zipfile.ZipFile(temporary, 'w') as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    temporary.replace(output)
    print('Developer bundle:', output)
    print('SHA256:', digest(output.read_bytes()))


if __name__ == '__main__':
    main()
