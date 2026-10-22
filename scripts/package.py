#!/usr/bin/env python3
"""Package a local APK and tracked public source; never deploy or publish."""
import hashlib
import pathlib
import re
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
config = (root / 'app/build.gradle').read_text()
version = re.search(r"versionName '([^']+)'", config).group(1).removesuffix('-standalone')
apk_input = root / 'app/build/outputs/apk/debug/app-debug.apk'
if not apk_input.exists():
    raise SystemExit('Build :app:assembleDebug before packaging')
out = root / 'releases' / version
out.mkdir(parents=True, exist_ok=True)
base = f'LibreTV-Standalone-{version}'
apk = out / f'{base}.apk'
shutil.copy2(apk_input, apk)
tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root, text=True).split('\0')
allowed_files = {'LICENSE', 'NOTICE.md', 'README.md', '.gitignore', 'build.gradle',
                 'gradle.properties', 'settings.gradle', 'gradlew', 'gradlew.bat',
                 'app/build.gradle', 'app/gradle.lockfile'}
selected = sorted(p for p in tracked if p and (p in allowed_files or p.startswith(
    ('app/src/', 'gradle/', 'third-party/upnpcast/', 'scripts/', 'config/'))))
for name in selected:
    assert not any(part in name for part in ('household.properties', 'local.properties', '.jks', '.keystore', '/build/'))
source = out / f'{base}-source.zip'
with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in selected:
        if (root / name).is_file(): archive.write(root / name, 'libretv-standalone/' + name)
    archive.writestr('libretv-standalone/SOURCE_VERSION.txt', subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True))
checksums = out / 'SHA256SUMS.txt'
checksums.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in (apk, source)))
print(f'Local artifacts: {out}\nAPK may contain local sources. Do not publish it without checking the embedded configuration.')
