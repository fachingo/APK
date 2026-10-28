#!/usr/bin/env bash
# Rebuild the pinned open-source OCR library without contacting JitPack.
# Output is separate from the verified library used by the Android app.
set -euo pipefail
recognition_repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
recognition_build_root="$recognition_repo_root/build/vendor-tesseract"
mkdir -p "$recognition_build_root"
export RECOGNITION_REPO_ROOT="$recognition_repo_root"
export RECOGNITION_BUILD_ROOT="$recognition_build_root"
python3 - <<'PY'
from pathlib import Path
import os,hashlib,tarfile,urllib.request,zipfile
root=Path(os.environ['RECOGNITION_BUILD_ROOT'])
def artifact(url,name,expected):
 p=root/name
 if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=expected:urllib.request.urlretrieve(url,p)
 assert hashlib.sha256(p.read_bytes()).hexdigest()==expected, 'Source/tool integrity verification failed'
 return p
source=artifact('https://codeload.github.com/adaptech-cz/Tesseract4Android/tar.gz/refs/tags/4.9.0','Tesseract4Android-4.9.0.tar.gz','4569f9093d641d13188ba2dd36a74396661eaa3f9e44637ff1cac9a36076a20e')
if not (root/'source/build.gradle').exists():
 (root/'source').mkdir(exist_ok=True)
 with tarfile.open(source) as archive:
  members=archive.getmembers();prefix=members[0].name.split('/')[0]+'/'
  for entry in members:
   if not entry.name.startswith(prefix):continue
   entry.name=entry.name[len(prefix):]
   if entry.name:archive.extract(entry,root/'source',filter='data')
gradle=artifact('https://downloads.gradle.org/distributions/gradle-8.13-bin.zip','gradle-8.13-bin.zip','20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78')
if not (root/'gradle-8.13/bin/gradle').exists():
 with zipfile.ZipFile(gradle) as z:z.extractall(root)
 (root/'gradle-8.13/bin/gradle').chmod(0o755)
PY
cat > "$recognition_build_root/arm64.init.gradle" <<'GRADLE'
allprojects { p ->
    p.afterEvaluate {
        if (p.name == 'tesseract4android') {
            p.android.defaultConfig.ndk.abiFilters.clear()
            p.android.defaultConfig.ndk.abiFilters.add('arm64-v8a')
        }
    }
}
GRADLE
# Requires JDK 21, platform 35, NDK 27.2.12479018 and CMake 3.22.1.
# Install them with SDK Manager first; keep inherited TLS/proxy settings enabled.
cd "$recognition_build_root/source"
JITPACK=true CMAKE_BUILD_PARALLEL_LEVEL=3 "$recognition_build_root/gradle-8.13/bin/gradle" --no-daemon --max-workers=3 -I "$recognition_build_root/arm64.init.gradle" :tesseract4android:assembleStandardRelease
mkdir -p "$recognition_build_root/output"
cp tesseract4android/build/outputs/aar/tesseract4android-standard-release.aar "$recognition_build_root/output/"
sha256sum "$recognition_build_root/output/tesseract4android-standard-release.aar"
# app/libs and its expected checksum are deliberately unchanged.
