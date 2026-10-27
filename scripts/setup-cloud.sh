#!/usr/bin/env bash
# Reproduce this workspace without changing repository source or lockfiles.
set -euo pipefail
cd /workspace
mkdir -p .tools .setup .data
python3 - <<'PY'
from pathlib import Path
import hashlib, os, shutil, subprocess, tarfile, urllib.request, urllib.parse, zipfile
root=Path('/workspace/.tools')
def download(url,path,expected):
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:
        urllib.request.urlretrieve(url,path)
    assert hashlib.sha256(path.read_bytes()).hexdigest()==expected, 'Artifact checksum mismatch'
jdk=root/'jdk-21.0.13+9'
if not (jdk/'bin/javac').exists():
    archive=root/'jdk.tar.gz'
    download('https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz',archive,'d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c')
    with tarfile.open(archive) as t:t.extractall(root,filter='data')
cli=root/'android-sdk/cmdline-tools/latest'
if not (cli/'bin/sdkmanager').exists():
    # Verify Google's published checksum before using the SDK bootstrap.
    import xml.etree.ElementTree as E
    metadata=E.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml').read())
    name='commandlinetools-linux-13114758_latest.zip'
    expected=next(a.findtext('./complete/checksum') for a in metadata.findall('.//archive') if a.findtext('./complete/url')==name)
    archive=root/'android-tools.zip';urllib.request.urlretrieve('https://dl.google.com/android/repository/'+name,archive)
    assert hashlib.sha1(archive.read_bytes()).hexdigest()==expected, 'Google SDK checksum mismatch'
    cli.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:z.extractall(cli.parent)
    (cli.parent/'cmdline-tools').rename(cli)
    for f in (cli/'bin').iterdir():f.chmod(0o755)
trust=root/'cacerts'
shutil.copyfile(jdk/'lib/security/cacerts',trust)
ca=os.environ.get('CODEX_PROXY_CERT')
if ca and Path(ca).is_file():
    subprocess.run([str(jdk/'bin/keytool'),'-importcert','-noprompt','-alias','cloud-session-ca','-file',ca,'-keystore',str(trust),'-storepass','changeit'],check=True)
proxy=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',''))
options=f'-Djavax.net.ssl.trustStore={trust}'
if proxy.hostname:
    options+=f' -Dhttps.proxyHost={proxy.hostname} -Dhttps.proxyPort={proxy.port or 8080} -Dhttp.proxyHost={proxy.hostname} -Dhttp.proxyPort={proxy.port or 8080} -Dhttp.nonProxyHosts=localhost|127.*'
# Only configuration selectors are saved; no credentials or process environment dump.
activation=f'''export JAVA_HOME={jdk}
export ANDROID_HOME={root}/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_USER_HOME={root}/android-user
export GRADLE_USER_HOME={root}/gradle-home
export PLAYWRIGHT_BROWSERS_PATH={root}/ms-playwright
export PATH=$JAVA_HOME/bin:{root}/venv/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
export JAVA_TOOL_OPTIONS='{options}'
export SNAPIMS_DATA_DIR=/workspace/.data/snapims-dev
export SNAPIMS_PROJECT_PATH=/workspace/SnapIMS
'''
(root/'activate').write_text(activation)
(root/'android-user').mkdir(exist_ok=True)
PY
source /workspace/.tools/activate
if [ ! -x /workspace/.tools/venv/bin/python ]; then python3 -m venv /workspace/.tools/venv; fi
/workspace/.tools/venv/bin/python -m pip install -e '/workspace/SnapIMS[dev]' -r /workspace/SnapIMS/requirements.txt
# Retain the original pricing release archive unchanged; extract outside checkout.
python3 - <<'PY'
import zipfile
from pathlib import Path
folder=Path('/workspace/.tools/pricing-src')
if not (folder/'pricing-workbench-v0.1.0/pyproject.toml').exists():
    with zipfile.ZipFile('/workspace/Pricing_module/Pricing-Workbench-v0.1.0.zip') as z:z.extractall(folder)
PY
if [ ! -x /workspace/.tools/pricing-venv/bin/python ]; then python3 -m venv /workspace/.tools/pricing-venv; fi
/workspace/.tools/pricing-venv/bin/python -m pip install -e '/workspace/.tools/pricing-src/pricing-workbench-v0.1.0[dev]' 'streamlit==1.50.0'
# Finite noninteractive input; preserve sdkmanager's actual exit status.
python3 -c 'print("y\n" * 100)' > /workspace/.setup/license-input
sdkmanager --licenses < /workspace/.setup/license-input
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
cd /workspace/APK
./gradlew --no-daemon :core:test :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
cd /workspace/SnapIMS
python -m pytest tests/test_mobile_import.py tests/test_processor.py tests/test_protocol_extended.py tests/test_db_migration.py tests/test_import_v0130.py

cd /workspace/.tools/pricing-src/pricing-workbench-v0.1.0
/workspace/.tools/pricing-venv/bin/python -m pytest
