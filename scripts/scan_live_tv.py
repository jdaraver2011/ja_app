#!/usr/bin/env python3
"""Build a US adaptive-live catalog from iptv-org. Never promote finite or frozen playlists."""
import argparse, concurrent.futures, datetime, hashlib, json, os, re, threading, time
import urllib.parse, urllib.request, xml.etree.ElementTree as ET
from collections import Counter, defaultdict

API='https://iptv-org.github.io/api/'
HOSTS=defaultdict(lambda:threading.BoundedSemaphore(3))
LOCK=threading.Lock()

def fetch(url,limit=524288,headers=None):
    parsed=urllib.parse.urlparse(url)
    if parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username:raise ValueError('unsupported URL')
    with LOCK: sem=HOSTS[parsed.hostname]
    with sem:
        req=urllib.request.Request(url,headers={'User-Agent':'JA-Live-Catalog/1.0 (+https://github.com/jdaraver2011/ja_app)','Cache-Control':'no-cache',**(headers or {})})
        with urllib.request.urlopen(req,timeout=8) as r:
            b=r.read(limit+1)
            if len(b)>limit:raise ValueError('response too large')
            return b.decode('utf-8-sig'),r.url

def hls_snapshot(text):
    if not text.lstrip().startswith('#EXTM3U'):return {'state':'unsupported'}
    if '#EXT-X-ENDLIST' in text or re.search(r'#EXT-X-PLAYLIST-TYPE:\s*VOD',text):return {'state':'ended'}
    if '#EXTINF:' not in text:return {'state':'unsupported'}
    lines=[x.strip() for x in text.splitlines() if x.strip()]
    segments=[x for x in lines if not x.startswith('#')]
    if not segments:return {'state':'unsupported'}
    seq=re.search(r'#EXT-X-MEDIA-SEQUENCE:(\d+)',text)
    dates=re.findall(r'#EXT-X-PROGRAM-DATE-TIME:([^\r\n]+)',text)
    return {'state':'live','signature':(int(seq.group(1)) if seq else 0,urllib.parse.urlparse(segments[-1]).path,dates[-1] if dates else '')}

def mpd_snapshot(text):
    if '<!DOCTYPE' in text.upper():return {'state':'unsupported'}
    root=ET.fromstring(text)
    if root.tag.rsplit('}',1)[-1]!='MPD':return {'state':'unsupported'}
    if root.attrib.get('type')!='dynamic':return {'state':'ended'}
    return {'state':'live','signature':hashlib.sha256(text.encode()).hexdigest()}

def probe(item):
    try:
        headers={k:v for k,v in [('User-Agent',item.get('userAgent')),('Referer',item.get('referrer'))] if v}
        text,base=fetch(item['url'],headers=headers)
        if text.lstrip().startswith('#EXTM3U'):
            variants=[];pending=None
            for line in text.splitlines():
                if line.startswith('#EXT-X-STREAM-INF:'):
                    n=re.search(r'(?:^|,)BANDWIDTH=(\d+)',line.split(':',1)[1]);pending=int(n.group(1)) if n else 999999999
                elif pending is not None and line.strip() and not line.startswith('#'):
                    variants.append((pending,urllib.parse.urljoin(base,line.strip())));pending=None
            if variants:text,_=fetch(min(variants)[1],headers=headers)
            out=hls_snapshot(text);out['type']='hls'
        else:
            out=mpd_snapshot(text);out['type']='dash'
        out['sampled']=time.monotonic();return out
    except Exception:return {'state':'error','sampled':time.monotonic()}

def advancing(first,second):
    return first.get('state')=='live' and second.get('state')=='live' and first.get('type')==second.get('type') and first.get('signature')!=second.get('signature')

def inputs():
    metadata=json.loads(fetch(API+'channels.json',16_000_000)[0])
    us={x['id']:x for x in metadata if x.get('country')=='US' and not x.get('is_nsfw') and not x.get('closed')}
    streams=json.loads(fetch(API+'streams.json',20_000_000)[0])
    playlist=fetch('https://iptv-org.github.io/iptv/countries/us.m3u',4_000_000)[0]
    logos={}
    for line in playlist.splitlines():
        if line.startswith('#EXTINF:'):
            ident=re.search(r'tvg-id="([^"]+)"',line);logo=re.search(r'tvg-logo="([^"]+)"',line)
            if ident and logo:logos[ident.group(1).split('@')[0]]=logo.group(1)
    result=[];seen=set()
    for s in streams:
        cid=s.get('channel');url=s.get('url','')
        if cid not in us or not url.startswith(('http://','https://')):continue
        feed=s.get('feed') or ''
        key=(cid,feed,url)
        if key in seen:continue
        seen.add(key)
        result.append({'id':cid+('@'+feed if feed else ''),'channel':cid,'name':us[cid]['name'],
            'logo':logos.get(cid,''),'url':url,'userAgent':s.get('user_agent') or '', 'referrer':s.get('referrer') or ''})
    return result

def scan(output,report,workers=24):
    started=time.time();items=inputs();print('Candidate streams:',len(items),flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        first=list(pool.map(probe,items))
        live=[i for i,x in enumerate(first) if x['state']=='live']
        print('Open-ended first samples:',len(live),flush=True)
        def again(i):
            remaining=12-(time.monotonic()-first[i]['sampled'])
            if remaining>0:time.sleep(remaining)
            return i,probe(items[i])
        second=dict(pool.map(again,live))
    channels={};reasons=Counter()
    for i,item in enumerate(items):
        a=first[i];b=second.get(i,{})
        if not advancing(a,b):reasons[a['state'] if a['state']!='live' else ('frozen' if b.get('state')=='live' else b.get('state','error'))]+=1;continue
        c=channels.setdefault(item['id'],{'id':item['id'],'name':item['name'],'logo':item['logo'],'streams':[]})
        c['streams'].append({k:item[k] for k in ['url','userAgent','referrer']})
    entries=sorted(channels.values(),key=lambda c:c['name'].casefold())
    previous={}
    if os.path.exists(output):
        with open(output) as f:previous=json.load(f)
    if not entries or (len(previous.get('channels',[]))>40 and len(entries)<len(previous['channels'])*0.25):raise RuntimeError('Unusually empty scan; preserving last successful catalog')
    now=datetime.datetime.now(datetime.timezone.utc).isoformat()
    counts={'candidateStreams':len(items),'verifiedChannels':len(entries),'verifiedStreams':sum(len(x['streams']) for x in entries),'excluded':dict(reasons),'seconds':round(time.time()-started)}
    doc={'schema':1,'generatedAt':now,'source':'iptv-org/api','verification':'Two distinct advancing adaptive-live playlist snapshots; no finite clips. Availability can change.','counts':counts,'channels':entries}
    for path,data in [(output,doc),(report,{'generatedAt':now,**counts})]:
        with open(path+'.tmp','w') as f:json.dump(data,f,ensure_ascii=False,indent=2);f.write('\n')
        os.replace(path+'.tmp',path)
    print(json.dumps(counts),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',default='catalog.json');p.add_argument('--report',default='scan-report.json');p.add_argument('--workers',type=int,default=24)
    a=p.parse_args();scan(a.output,a.report,a.workers)
