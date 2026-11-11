import os
from pathlib import Path

def escape(value):
    return value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('=', '\\=').replace(':', '\\:').replace(' ', '\\ ')

key = os.environ.get('TMDB_API_KEY', '').strip()
if not key:
    raise SystemExit('Add TMDB_API_KEY under GitHub Settings > Secrets and variables > Actions.')
values = {name: os.environ.get(name, '') for name in (
    'TMDB_API_KEY', 'AUTHORIZED_EMBED_TEMPLATE', 'AUTHORIZED_EMBED_HOST',
    'DIRECT_MEDIA_TEMPLATE', 'DIRECT_MEDIA_HOST')}
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if sdk:
    values['sdk.dir'] = sdk
Path('local.properties').write_text(''.join(f'{name}={escape(value)}\n' for name, value in values.items()))
print('Build properties configured; secret values are not printed.')
