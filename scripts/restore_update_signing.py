"""Restore an existing signing keystore from CI secrets without printing its bytes."""
import base64, os
from pathlib import Path

value=os.environ.get('JA_KEYSTORE_BASE64','')
required=os.environ.get('PUBLISH_UPDATE')=='true'
if not value:
    if required:raise SystemExit('Publishing requires JA_KEYSTORE_BASE64 and the matching signing secrets.')
    print('No persistent signing key configured; build only, update publication disabled.')
else:
    if not all(os.environ.get(k) for k in ['JA_KEYSTORE_PASSWORD','JA_KEY_ALIAS','JA_KEY_PASSWORD']):
        raise SystemExit('Set JA_KEYSTORE_PASSWORD, JA_KEY_ALIAS and JA_KEY_PASSWORD.')
    path=Path(os.environ['RUNNER_TEMP'])/'ja-update.keystore'
    path.write_bytes(base64.b64decode(value,validate=True));path.chmod(0o600)
    with open(os.environ['GITHUB_ENV'],'a') as stream:stream.write(f'JA_SIGNING_STORE={path}\n')
    print('Persistent signing key restored for this build.')
