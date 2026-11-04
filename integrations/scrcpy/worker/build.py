"""Build the pinned scrcpy server + KigEyes panel lease with Android SDK/JDK.

Run on Windows; all compilation happens on local disk, never on the NAS.
Based on upstream server/build_without_gradle.sh. No installed app or root needed.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

REVISION = 'a60891aea193d92e7e5c3942700eca63f9d19a5f'
VERSION = '5.0.1-kigeyes.11'
HERE = Path(__file__).resolve().parent
CACHE = Path.home()/'.cache'/'kigeyes-scrcpy'
SDK = Path(os.environ.get('ANDROID_HOME',str(Path(os.environ['LOCALAPPDATA'])/'Android'/'Sdk')))
JDK = Path(os.environ.get('SCRCPY_JAVA_HOME',r'C:\Program Files\Android\Android Studio\jbr'))


def run(args, **kwargs):
    subprocess.run([str(a) for a in args],check=True,**kwargs)


def response(path, args):
    path.write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
    return '@'+str(path)


def main():
    CACHE.mkdir(parents=True,exist_ok=True)
    upstream = CACHE/'upstream'
    if not upstream.exists():
        run(['git','clone','--depth','1','--branch','v5.0.1','https://github.com/Genymobile/scrcpy.git',upstream])
    actual = subprocess.check_output(['git','-C',str(upstream),'rev-parse','v5.0.1^{commit}'],text=True).strip()
    if actual != REVISION: raise RuntimeError('Upstream revision mismatch')
    android = SDK/'platforms'/'android-37.0'/'android.jar'
    build_tools = SDK/'build-tools'/'36.0.0'
    if not android.exists(): raise RuntimeError('Install Android SDK platform 37.0')
    # TemporaryDirectory owns only its freshly allocated directory below CACHE.
    with tempfile.TemporaryDirectory(prefix='build-',dir=CACHE) as directory:
        root = Path(directory)
        archive = subprocess.check_output(['git','-C',str(upstream),'archive',REVISION])
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(root,filter='data')
        run(['git','apply','--check',HERE/'panel-watchdog.patch'],cwd=root)
        run(['git','apply',HERE/'panel-watchdog.patch'],cwd=root)
        run(['git','apply','--check',HERE/'keyframe-request.patch'],cwd=root)
        run(['git','apply',HERE/'keyframe-request.patch'],cwd=root)
        run(['git','apply','--check',HERE/'direct-peer.patch'],cwd=root)
        run(['git','apply',HERE/'direct-peer.patch'],cwd=root)
        run(['git','apply','--check',HERE/'guarded-panel.patch'],cwd=root)
        run(['git','apply',HERE/'guarded-panel.patch'],cwd=root)
        run(['git','apply','--check',HERE/'panel-state.patch'],cwd=root)
        run(['git','apply',HERE/'panel-state.patch'],cwd=root)
        java = root/'server/src/main/java'
        target = java/'com/genymobile/scrcpy/control/PanelPowerLease.java'
        shutil.copy2(HERE/'PanelPowerLease.java',target)
        for name in ('DirectAgent.java','DirectActions.java','PinnedTrust.java','AwakePolicy.java','WorkerLocalControl.java','WorkerAccess.java','WorkerProvider.java','CompanionNetwork.java','NetworkRouteLease.java','GuardPlatform.java','ControlRelay.java'):
            shutil.copy2(HERE/name,java/'com/genymobile/scrcpy'/name)
        shutil.copy2(HERE/'GuardedPanel.java',java/'com/genymobile/scrcpy/control/GuardedPanel.java')
        tests = root/'lease-tests';tests.mkdir()
        run([JDK/'bin/javac.exe','-d',tests,HERE/'NetworkRouteLease.java',HERE/'NetworkRouteLeaseTest.java'])
        run([JDK/'bin/java.exe','-cp',tests,'com.genymobile.scrcpy.NetworkRouteLeaseTest'])
        run([JDK/'bin/javac.exe','-d',tests,HERE/'GuardedPanel.java',HERE/'GuardedPanelTest.java'])
        run([JDK/'bin/java.exe','-cp',tests,'com.genymobile.scrcpy.control.GuardedPanelTest'])
        run([JDK/'bin/javac.exe','-d',tests,HERE/'PanelPowerLease.java',HERE/'PanelPowerLeaseTest.java'])
        run([JDK/'bin/java.exe','-cp',tests,'com.genymobile.scrcpy.control.PanelPowerLeaseTest'])
        run([JDK/'bin/keytool.exe','-genkeypair','-alias','test','-dname','CN=receiver-test',
             '-keyalg','RSA','-validity','1','-keystore',root/'test.p12','-storepass','test-only-password',
             '-keypass','test-only-password'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        run([JDK/'bin/keytool.exe','-exportcert','-alias','test','-keystore',root/'test.p12',
             '-storepass','test-only-password','-file',root/'test.crt'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        run([JDK/'bin/javac.exe','-d',tests,HERE/'PinnedTrust.java',HERE/'DirectActions.java',HERE/'DirectSecurityTest.java'])
        run([JDK/'bin/java.exe','-cp',tests,'com.genymobile.scrcpy.DirectSecurityTest',root/'test.crt'])
        run([JDK/'bin/javac.exe','-d',tests,HERE/'AwakePolicy.java',HERE/'AwakePolicyTest.java'])
        run([JDK/'bin/java.exe','-cp',tests,'com.genymobile.scrcpy.AwakePolicyTest'])
        run([JDK/'bin/javac.exe','-d',tests,HERE/'WorkerAccess.java',HERE/'WorkerAccessTest.java'])
        run([JDK/'bin/java.exe','-cp',tests,'com.genymobile.scrcpy.WorkerAccessTest'])
        key_tests=root/'keyframe-tests';key_tests.mkdir()
        run([JDK/'bin/javac.exe','-d',key_tests,
             java/'com/genymobile/scrcpy/video/CaptureControl.java',*sorted((HERE/'tests').rglob('*.java'))])
        run([JDK/'bin/java.exe','-cp',key_tests,'com.genymobile.scrcpy.video.KeyframeRequestTest'])
        gen = root/'gen';gen.mkdir()
        config = gen/'com/genymobile/scrcpy/BuildConfig.java';config.parent.mkdir(parents=True)
        config.write_text('package com.genymobile.scrcpy; public final class BuildConfig { '
                          'public static final boolean DEBUG=false; '
                          f'public static final String VERSION_NAME="{VERSION}"; }}',encoding='utf-8')
        aidl = root/'server/src/main/aidl'
        for name in ('android/content/IOnPrimaryClipChangedListener.aidl','android/view/IDisplayWindowListener.aidl'):
            run([build_tools/'aidl.exe','-o'+str(gen),'-I'+str(aidl),'-p'+str(android.parent/'framework.aidl'),aidl/name])
        classes = root/'classes';classes.mkdir()
        args = ['-encoding','UTF-8','-bootclasspath',android,'-cp',str(build_tools/'core-lambda-stubs.jar')+os.pathsep+str(gen),
                '-d',classes,'-source','1.8','-target','1.8',*sorted(java.rglob('*.java')),*sorted(gen.rglob('*.java'))]
        run([JDK/'bin/javac.exe',response(root/'javac.args',args)])
        run([JDK/'bin/javac.exe','-cp',str(classes)+os.pathsep+str(android),'-d',tests,HERE/'PanelProtocolTest.java'])
        run([JDK/'bin/java.exe','-cp',str(tests)+os.pathsep+str(classes)+os.pathsep+str(android),
             'com.genymobile.scrcpy.control.PanelProtocolTest'])
        run([JDK/'bin/javac.exe','-cp',str(classes)+os.pathsep+str(android),'-d',tests,HERE/'ControlRelayTest.java'])
        run([JDK/'bin/java.exe','-cp',str(tests)+os.pathsep+str(classes)+os.pathsep+str(android),'com.genymobile.scrcpy.ControlRelayTest'])
        dex = root/'dex';dex.mkdir()
        args = ['--min-api','31','--classpath',android,'--output',dex,*sorted(classes.rglob('*.class'))]
        # Java's argfile parser handles quoted paths; D8's own @file parser does not.
        run([JDK/'bin/java.exe',response(root/'d8.args',['-cp',build_tools/'lib/d8.jar','com.android.tools.r8.D8',*args])])
        # Android framework default-method companions do not exist on-device.
        # A missing min-api previously made a DeathRecipient lambda crash on app exit.
        for path in dex.glob('*.dex'):
            if b'Landroid/os/IBinder$DeathRecipient$-CC;' in path.read_bytes():
                raise RuntimeError('Invalid desugared framework Binder callback')
        # Fixed ZIP metadata makes the binary hash stable across repeated builds.
        import zipfile
        artifact = CACHE/f'scrcpy-server-v{VERSION}'
        with zipfile.ZipFile(artifact,'w') as jar:
            for path in sorted(dex.glob('*.dex')):
                info=zipfile.ZipInfo(path.name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                jar.writestr(info,path.read_bytes())
        manifest=dict(upstream_revision=REVISION,version=VERSION,android_platform='37.0',build_tools='36.0.0',min_api=31,
                      sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
                      patch_sha256=hashlib.sha256((HERE/'panel-watchdog.patch').read_bytes()).hexdigest(),
                      keyframe_patch_sha256=hashlib.sha256((HERE/'keyframe-request.patch').read_bytes()).hexdigest(),
                      direct_patch_sha256=hashlib.sha256((HERE/'direct-peer.patch').read_bytes()).hexdigest(),
                      direct_sources_sha256={name:hashlib.sha256((HERE/name).read_bytes()).hexdigest() for name in ('DirectAgent.java','DirectActions.java','PinnedTrust.java','AwakePolicy.java','WorkerLocalControl.java','WorkerAccess.java','WorkerProvider.java','CompanionNetwork.java','NetworkRouteLease.java','GuardPlatform.java','ControlRelay.java')},
                      panel_state_patch_sha256=hashlib.sha256((HERE/'panel-state.patch').read_bytes()).hexdigest(),
                      guard_patch_sha256=hashlib.sha256((HERE/'guarded-panel.patch').read_bytes()).hexdigest(),
                      guard_policy_sha256=hashlib.sha256((HERE/'GuardedPanel.java').read_bytes()).hexdigest(),
                      lease_sha256=hashlib.sha256((HERE/'PanelPowerLease.java').read_bytes()).hexdigest())
        (CACHE/'build-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        print(json.dumps(dict(artifact=str(artifact),**manifest),indent=2))


if __name__=='__main__': main()
