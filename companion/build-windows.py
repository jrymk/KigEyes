"""Build locally: Java/Gradle cannot reliably use mapped NAS drives on this host."""
import os
from pathlib import Path
import shutil
import subprocess
import re
import argparse
from build_support import stage_sources

source = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check-release', action='store_true', help='Also compile and lint the release variant (no signing/deployment)')
args = parser.parse_args()
stage = Path.home() / '.cache' / 'kigeyes-android'
stage_sources(source, stage)
env = dict(os.environ)
configured_java = Path(env.get('JAVA_HOME', r'C:\Program Files\Android\Android Studio\jbr'))
release = (configured_java / 'release').read_text(encoding='utf-8') if (configured_java / 'release').exists() else ''
major = re.search(r'JAVA_VERSION="(\d+)', release)
if not major or int(major.group(1)) < 17:
    configured_java = Path(r'C:\Program Files\Android\Android Studio\jbr')
env['JAVA_HOME'] = str(configured_java)
env.setdefault('ANDROID_HOME', str(Path(os.environ['LOCALAPPDATA']) / 'Android' / 'Sdk'))
gradle = stage / 'gradlew.bat'
tasks = ['assembleDebug', 'testDebugUnitTest', 'lintDebug']
if args.check_release:
    tasks += ['assembleRelease', 'lintRelease']
subprocess.run([str(gradle), '-p', str(stage), *tasks, '--console=plain'], cwd=stage, env=env, check=True)
destination = source / 'app' / 'build' / 'outputs' / 'apk' / 'debug'
destination.mkdir(parents=True, exist_ok=True)
shutil.copy2(stage / 'app' / 'build' / 'outputs' / 'apk' / 'debug' / 'app-debug.apk', destination)
print('APK:', destination / 'app-debug.apk')
