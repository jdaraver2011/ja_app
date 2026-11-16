"""Create public update assets from a built APK. Does not publish anything."""
import argparse, hashlib, json, re, shutil, subprocess
from pathlib import Path

def prepare(apk, repo, aapt, destination):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Invalid update repository; expected owner/repository')
    output = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not match:
        raise ValueError('Could not read APK version metadata')
    package, code, name = match.groups()
    if package != 'com.example.ja' or not re.fullmatch(r'[A-Za-z0-9._-]+', name):
        raise ValueError('Unexpected APK package or version')
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / 'JA-update.apk'
    shutil.copyfile(apk, target)
    with target.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    tag = 'v' + name
    metadata = dict(packageName=package, versionCode=int(code), versionName=name,
        size=target.stat().st_size, sha256=digest,
        apkUrl=f'https://github.com/{repo}/releases/download/{tag}/JA-update.apk')
    (destination/'ja-update.json').write_text(json.dumps(metadata, indent=2)+'\n')
    return tag, name

if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True)
    p.add_argument('--repo',required=True);p.add_argument('--aapt',type=Path,required=True)
    p.add_argument('--output',type=Path,default=Path('dist'))
    args=p.parse_args();tag,name=prepare(args.apk,args.repo,args.aapt,args.output)
    print(f'Prepared {tag}; versionCode and SHA-256 read from the built APK.')
