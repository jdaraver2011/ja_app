"""Release gate: require a validated U.S. catalog with all eighteen category sources scanned."""
import json,time,urllib.request
REQUIRED={'news','sports','movies','series','entertainment','documentary','kids','animation','comedy','music','cooking','travel','science','education','business','weather','classic','auto'}
u='https://raw.githubusercontent.com/jdaraver2011/ja_app/live-tv-data/catalog.json?release-check='+str(int(time.time()))
with urllib.request.urlopen(urllib.request.Request(u,headers={'Cache-Control':'no-cache'}),timeout=30) as response:data=json.load(response)
assert data['schema']==1 and data['channels'],'Catalog is empty or unsupported'
assert REQUIRED<=set(data['counts']['categories']),'Category counts missing'
assert {'category:'+c for c in REQUIRED}<=set(data['counts']['sources']),'One or more category playlists have not been scanned'
assert len({c['id'] for c in data['channels']})==len(data['channels']),'Duplicate channel identities'
assert all(c['streams'] and isinstance(c.get('categories'),list) and set(c['categories'])<=REQUIRED for c in data['channels']),'Invalid streams/category metadata'
print('Verified category catalog:',len(data['channels']),'channels across',len(REQUIRED),'requested categories')
