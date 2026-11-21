#!/usr/bin/env python3
"""Pinned MIT engine and presets, integrity checked before bundling. No runtime network scripts."""
import base64, hashlib, io, pathlib, tarfile, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/assets/music-visualizer'
PACKAGES = [
 ('butterchurn', '2.6.7', 'BJiRA8L0L2+84uoG2SSfkp0kclBuN+vQKf217pK7pMlwEO2ZEg3MtO2/o+l8Qpr8Nbejg8tmL1ZHD1jmhiaaqg==', 'package/lib/butterchurn.min.js', 'butterchurn.min.js'),
 ('butterchurn-presets', '2.4.7', '4MdM8ripz/VfH1BCldrIKdAc/1ryJFBDvqlyow6Ivo1frwj0H3duzvSMFC7/wIjAjxb1QpwVHVqGqS9uAFKhpg==', 'package/lib/butterchurnPresets.min.js', 'presets.min.js')]
OUT.mkdir(parents=True, exist_ok=True)
for name, version, digest, member, target in PACKAGES:
    data = urllib.request.urlopen(f'https://registry.npmjs.org/{name}/-/{name}-{version}.tgz', timeout=60).read()
    assert base64.b64encode(hashlib.sha512(data).digest()).decode() == digest, 'Package integrity mismatch'
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        (OUT / target).write_bytes(archive.extractfile(member).read())
        license_member = next(m for m in archive.getmembers() if m.name.upper() == 'PACKAGE/LICENSE')
        (OUT / f'{name}-LICENSE.txt').write_bytes(archive.extractfile(license_member).read())
    print(f'Bundled {name} {version} (verified SHA-512)')
