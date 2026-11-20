"""Build pinned MIT WeatherStar client assets; no external hosting required."""
import hashlib,json,pathlib,shutil,subprocess,tarfile,tempfile,urllib.request
PIN='1ccc90154f0fc9d5da1df0405f814bad435f27d7'
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'app/src/main/assets/weatherstar'
with tempfile.TemporaryDirectory() as tmp:
    tmp=pathlib.Path(tmp);archive=tmp/'source.tgz'
    urllib.request.urlretrieve(f'https://api.github.com/repos/jacaudi/ws4kp/tarball/{PIN}',archive)
    with tarfile.open(archive) as t:t.extractall(tmp/'source',filter='data')
    source=next((tmp/'source').iterdir());web=source/'web'
    # Only redistribute Latest Observations, CC BY 4.0; exclude the noncommercial album.
    music=web/'server/music/default';track='latest-observations--sunny-afternoon.mp3'
    pointer=(music/track).read_text();expected=dict(line.split(' ',1) for line in pointer.strip().splitlines())['oid'].split(':')[1]
    for f in music.glob('*.mp3'):f.unlink()
    urllib.request.urlretrieve(f'https://media.githubusercontent.com/media/jacaudi/ws4kp/{PIN}/web/server/music/default/{track}',music/track)
    assert hashlib.sha256((music/track).read_bytes()).hexdigest()==expected,'Music LFS digest mismatch'
    subprocess.run(['npm','ci','--no-audit','--no-fund'],cwd=web,check=True)
    subprocess.run(['npm','run','build'],cwd=web,check=True)
    if out.exists():shutil.rmtree(out)
    shutil.copytree(web/'dist',out)
    for f in out.rglob('*.map'):f.unlink()
    shutil.copy(source/'LICENSE',out/'LICENSE.txt')
    (out/'ATTRIBUTION.txt').write_text('WeatherStar 4000+ by Matt Walsh, jacaudi and contributors (MIT).\nSource: https://github.com/jacaudi/ws4kp/tree/'+PIN+'\nMusic: Sunny Afternoon, Latest Observations by netbymatt; CC BY 4.0. https://github.com/netbymatt/ws4kp-music https://creativecommons.org/licenses/by/4.0/\n')
    (out/'ja-version.json').write_text(json.dumps({'commit':PIN,'mode':'wide-enhanced','music':'CC BY 4.0'}))
    assert (out/'index.html').exists() and (out/'resources/ws.min.js').stat().st_size>10000
    print('Bundled pinned WeatherStar assets:',PIN)
