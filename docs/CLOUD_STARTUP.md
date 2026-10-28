# SnapIMS Mobile development startup

Each cloud task already runs in an isolated environment. Use the existing
/workspace/SnapIMS, /workspace/Pricing_module and /workspace/APK checkouts; do not
create a Git worktree unless the user explicitly requests one. Inspect git status
and preserve local work. Read the cloud-environment-runtime skill and current
network/credential status before network operations. Never print secret values.

Prepared dependencies persist under /workspace/.tools; source its activate file.
JDK 21.0.13, SDK platform 37.0, AGP 9.4.1, Gradle wrapper 9.8.2 and Kotlin 2.3.21
are prepared. Kotlin 2.4 metadata is not yet supported by the Room compiler.
Pricing uses a separate venv with Streamlit 1.50.0 for its documented UI tests.
If dependencies are absent, run the saved install script (also at
/workspace/APK/scripts/setup-cloud.sh). Do not modify existing repository dependency
manifests or lockfiles during setup. Do not assume live processes survive snapshots.

Start internal development services from /workspace with the tested script below
(also saved as /workspace/APK/scripts/start-cloud.sh). It reuses healthy processes,
checks database health and the Import page, and renders Pricing operator controls.
All data paths are disposable development roots, never production inventory.
Do not expose unauthenticated import endpoints or publish localhost preview links.

```bash
#!/usr/bin/env bash
set -euo pipefail
source /workspace/.tools/activate
mkdir -p /workspace/.setup /workspace/.data
cd /workspace/SnapIMS
if ! curl -fsS --max-time 5 http://127.0.0.1:8767/health > /workspace/.setup/snapims-health.json; then
    SNAPIMS_DATA_DIR=/workspace/.data/snapims-dev SNAPIMS_MANAGE_GUACAMOLE_SERVICES=false nohup python -m uvicorn snapims.web.app:app --host 127.0.0.1 --port 8767 > /workspace/.setup/snapims-server.log 2>&1 &
    echo "$!" > /workspace/.setup/snapims-server.pid
fi
for attempt in {1..20}; do
    if curl -fsS --max-time 2 http://127.0.0.1:8767/health > /workspace/.setup/snapims-health.json; then break; fi
    sleep 1
done
python - <<'PY'
import json
from pathlib import Path
result=json.loads(Path('/workspace/.setup/snapims-health.json').read_text())
assert result['status']=='ok' and result['components']['inventory_database']['state']=='healthy', result
print('SnapIMS development database healthy, schema',result['schema']['schema_version'])
PY
curl -fsS http://127.0.0.1:8767/import -o /workspace/.setup/import-page.html
python - <<'PY'
from pathlib import Path
assert 'import' in Path('/workspace/.setup/import-page.html').read_text().lower()
print('SnapIMS Import page rendered')
PY
cd /workspace/.tools/pricing-src/pricing-workbench-v0.1.0
if ! curl -fsS --max-time 5 http://127.0.0.1:8501/_stcore/health > /workspace/.setup/pricing-health.txt; then
    PRICING_WORKBENCH_DATABASE=/workspace/.data/pricing-dev.sqlite3 nohup /workspace/.tools/pricing-venv/bin/streamlit run app.py --server.headless=true --server.address=127.0.0.1 --server.port=8501 --browser.gatherUsageStats=false > /workspace/.setup/pricing-server.log 2>&1 &
    echo "$!" > /workspace/.setup/pricing-server.pid
fi
for attempt in {1..20}; do
    if curl -fsS --max-time 2 http://127.0.0.1:8501/_stcore/health > /workspace/.setup/pricing-health.txt; then break; fi
    sleep 1
done
python - <<'PY'
from pathlib import Path
assert Path('/workspace/.setup/pricing-health.txt').read_text().strip()=='ok'
print('Pricing development server healthy')
PY
PRICING_WORKBENCH_DATABASE=/workspace/.data/pricing-dev.sqlite3 /workspace/.tools/pricing-venv/bin/python - <<'PY'
from streamlit.testing.v1 import AppTest
app=AppTest.from_file('/workspace/.tools/pricing-src/pricing-workbench-v0.1.0/app.py').run(timeout=30)
assert not app.exception
assert app.title[0].value=='Pricing Workbench'
assert len(app.button)==2
print('Pricing operator controls rendered')
PY

```

To build/test Android, source /workspace/.tools/activate, cd /workspace/APK and run:
`./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
The installable debug APK is app/build/outputs/apk/debug/app-debug.apk. Host tests
use actual Room/files/images/ZIPs. Robolectric's missing directory-fd support is
replaced by a real host FileChannel.force; Android uses POSIX directory fsync.
No physical Pixel or /dev/kvm is available. Device navigation tests, real camera
quality vs Pixel Camera, force-stop timing, 500 MB physical rollover and 100 physical
products / 300 real photographs remain acceptance gates. See APK/docs/PIXEL_ACCEPTANCE.md.

From /workspace/SnapIMS, `python -m pytest tests/test_mobile_import.py tests/test_processor.py tests/test_protocol_extended.py tests/test_db_migration.py tests/test_import_v0130.py`
passes 86 checks. Full suite collection has four existing stale-import failures in
test_batch_recognition.py, test_interpreter.py, test_recognition.py and test_sorter.py.
Do not hide or disable those failures. They are unrelated to dependency setup.

Manual mobile import: `python -m snapims.mobile_import /path/to/part.zip --data-dir /workspace/.data/mobile-import-test --location Q1`.
Use an explicit disposable data root initially. Repeat imports must return the same
receipt/item IDs. Existing QR import, inventory ID allocation, pricing, recognition
and Shopify logic remain on desktop. Do not test Shopify writes against production.

Pricing source was extracted without modifying its original repository archive into
/workspace/.tools/pricing-src/pricing-workbench-v0.1.0. Run its suite there with
/workspace/.tools/pricing-venv/bin/python -m pytest: 61 passed, one browser skip.
Local extraction/parsing was also validated with system Chromium and an HTTP fixture.
The default downloaded Playwright browser and live eBay access are currently blocked
by network policy. Draft domain additions are cdn.playwright.dev and www.ebay.ca;
saving a draft does not apply them. After the user applies relevant settings, retry
`PLAYWRIGHT_BROWSERS_PATH=/workspace/.tools/ms-playwright /workspace/.tools/pricing-venv/bin/python -m playwright install chromium`
and the affected checks; do not claim live pricing access based on saved settings.

Logs/evidence: /workspace/.setup. Only stop servers started by this task; their PID
files are snapims-server.pid and pricing-server.pid in that directory. New source is
uncommitted for review. Milestone 1 is an acceptance build; wireless pairing,
OCR/OpenAI and chat are gated later work, explicitly tracked in APK/docs/STATUS.md.


## Application update 0.1.1

The newer README and RECOGNITION_0.1.1.md supersede older feature-limit statements.
OpenAI, offline Tesseract OCR and model-free ZXing/image checks are implemented.
The API key is entered on the Pixel and encrypted with Android Keystore; no cloud
API secret is needed to build. The app includes verified source-built arm64 native
libraries and English character data. The ordinary Gradle build verifies them and
needs no JitPack connection or native rebuild. There are 36 host tests. Wireless
synchronization and chat remain pending, as do physical device/live-key checks.
The desktop service startup commands and isolated data roots above are unchanged.
