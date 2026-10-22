#!/usr/bin/env python3
"""Validate empty-source APKs and prepare GitHub update manifests; does not publish."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, action='append', required=True)
parser.add_argument('--version', required=True)
parser.add_argument('--notes', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home()/'Library/Android/sdk'))
args = parser.parse_args()
assert re.fullmatch(r'\d+\.\d+\.\d+', args.version), 'Use a stable numeric version'
tools = args.sdk/'build-tools/36.0.0'
args.out.mkdir(parents=True, exist_ok=True)
notes = args.notes.read_text().strip()
assert len(notes) <= 2000
certificates, packages, outputs, codes = set(), set(), [], set()
for apk in args.apk:
    with zipfile.ZipFile(apk) as archive:
        assert json.loads(archive.read('assets/default-sources.json')) == {'sources': []}, 'Public APK must have zero bundled sources'
        if 'assets/default-live-sources.json' in archive.namelist():
            assert json.loads(archive.read('assets/default-live-sources.json')) == {'subscriptions': []}, 'Public APK must have zero bundled live subscriptions'
    signing = subprocess.check_output([str(tools/'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    certificates.add(re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', signing)[1])
    info = subprocess.check_output([str(tools/'aapt'), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", info)
    package, code, name = match[1], int(match[2]), match[3]
    assert package in ('family.libretv.standalone', 'family.libretv.tv') and package not in packages
    assert name.startswith(args.version+'-')
    packages.add(package); codes.add(code)
    filename = f'LibreTV-{args.version}'+('' if package == 'family.libretv.standalone' else '-'+package)+'.apk'
    target = args.out/filename
    if target.resolve() != apk.resolve(): shutil.copy2(apk, target)
    manifest = dict(packageName=package, versionCode=code, versionName=name,
                    minSdk=int(re.search(r"sdkVersion:'(\d+)'", info)[1]), size=target.stat().st_size,
                    sha256=hashlib.sha256(target.read_bytes()).hexdigest(), notes=notes,
                    downloadUrl=f'https://github.com/if-lensman/libretv-standalone/releases/download/v{args.version}/{filename}')
    metadata = args.out/f'update-{package}.json'
    metadata.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
    outputs += [target, metadata]
assert len(certificates) == 1 and len(codes) == 1, 'APKs must share signing identity and version code'
checksums = args.out/'SHA256SUMS.txt'
checksums.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in outputs))
print(json.dumps({'files': [str(p) for p in outputs+[checksums]], 'certificate': next(iter(certificates)), 'versionCode': next(iter(codes))}, indent=2))
