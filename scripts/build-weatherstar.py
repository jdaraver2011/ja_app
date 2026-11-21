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
    music=web/'server/music/default'
    tracks=['latest-observations--sunny-afternoon.mp3','latest-observations--morning-fog.mp3','latest-observations--september-drizzle.mp3']
    expected={track:dict(line.split(' ',1) for line in (music/track).read_text().strip().splitlines())['oid'].split(':')[1] for track in tracks}
    for f in music.glob('*.mp3'):f.unlink()
    for track in tracks:
        urllib.request.urlretrieve(f'https://media.githubusercontent.com/media/jacaudi/ws4kp/{PIN}/web/server/music/default/{track}',music/track)
        assert hashlib.sha256((music/track).read_bytes()).hexdigest()==expected[track],'Music LFS digest mismatch'
        info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration:stream=codec_name','-of','json',str(music/track)]))
        assert float(info['format']['duration'])>30 and any(x['codec_name']=='mp3' for x in info['streams'])
        subprocess.run(['ffmpeg','-v','error','-i',str(music/track),'-t','2','-f','null','-'],check=True)
        print('Decoded bundled audio:',track,info['format']['duration'],flush=True)
    media=web/'server/scripts/modules/media.mjs'
    raw=media.read_text();assert 'const startMedia = async () => {' in raw
    media.write_text(raw.replace('const startMedia = async () => {',"const startMedia = async () => {\nif (new URLSearchParams(window.location.search).get('jaKiosk') === 'true') return;"))
    scroll=web/'server/scripts/modules/currentweatherscroll.mjs'
    raw=scroll.read_text();needle='const { scrollWidth, clientWidth } = scrollElement;';assert needle in raw
    scroll.write_text(raw.replace(needle,needle+"\n\t\tscrollElement.style.textAlign = scrollWidth > clientWidth ? 'left' : 'center';"))
    scaling=web/'server/scripts/modules/utils/scaling.mjs' 
    raw=scaling.read_text()
    assert 'const resize = (force = false) => {' in raw
    raw="import fitJaKiosk from './ja-kiosk.mjs';\n"+raw.replace('const resize = (force = false) => {','const resize = (force = false) => {\n\tif (fitJaKiosk()) return;')
    scaling.write_text(raw)
    shutil.copy(root/'scripts/weatherstar/ja-kiosk.mjs',scaling.parent/'ja-kiosk.mjs')
    subprocess.run(['npm','ci' ,'--no-audit','--no-fund'],cwd=web,check=True)
    subprocess.run(['npm','run','build'],cwd=web,check=True)
    if out.exists():shutil.rmtree(out)
    shutil.copytree(web/'dist',out)
    for f in out.rglob('*.map'):f.unlink()
    with (out/'resources/ws.min.css').open('a') as css:css.write((root/'scripts/weatherstar/ja-ticker.css').read_text())
    (out/'playlist.json').write_text(json.dumps({'availableFiles':[],'metaData':{}}))
    for icon in (out/'images/icons/current-conditions').glob('*.gif'):
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(icon),'-frames:v','1',str(icon.with_suffix('.png'))],check=True)
    shutil.copy(source/'LICENSE',out/'LICENSE.txt')
    (out/'ATTRIBUTION.txt').write_text('WeatherStar 4000+ by Matt Walsh, jacaudi and contributors (MIT).\nSource: https://github.com/jacaudi/ws4kp/tree/'+PIN+'\nMusic: Sunny Afternoon, Morning Fog and September Drizzle, Latest Observations by netbymatt; CC BY 4.0. https://github.com/netbymatt/ws4kp-music https://creativecommons.org/licenses/by/4.0/\n')
    (out/'ja-version.json').write_text(json.dumps({'commit':PIN,'mode':'wide-enhanced','music':'CC BY 4.0'}))
    assert (out/'index.html').exists() and (out/'resources/ws.min.js').stat().st_size>10000
    print('Bundled pinned WeatherStar assets:',PIN)
