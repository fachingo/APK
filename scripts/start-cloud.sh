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
