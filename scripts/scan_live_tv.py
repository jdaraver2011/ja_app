#!/usr/bin/env python3
"""Build a US adaptive-live catalog from iptv-org and Free-TV/IPTV. Never promote finite or frozen playlists."""
import argparse, concurrent.futures, datetime, hashlib, json, os, re, threading, time
import urllib.parse, urllib.request, xml.etree.ElementTree as ET
from collections import Counter, defaultdict

API='https://iptv-org.github.io/api/'
CATEGORIES=('news','sports','movies','series','entertainment','documentary','kids','animation','comedy','music','cooking','travel','science','education','business','weather','classic','auto')
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

FREE_TV='https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8'

def m3u_attributes(line):
    return dict(re.findall(r'([\w-]+)="([^"]*)"',line))

def m3u_label(line):
    quoted=False
    for i,char in enumerate(line):
        if char=='"':quoted=not quoted
        if char==',' and not quoted:return line[i+1:].strip()
    return ''

def free_tv_us(raw,us_ids=None):
    """The source is worldwide. Import explicit US entries only, without changing its URLs."""
    entries=[];info=None;headers={}
    if not raw.lstrip().startswith('#EXTM3U'):raise ValueError('Invalid Free-TV playlist')
    for line in raw.splitlines():
        line=line.strip()
        if line.startswith('#EXTINF:'):info=line;headers={}
        elif line.startswith('#EXTVLCOPT:') and info:
            option,_,value=line[len('#EXTVLCOPT:'):].partition('=')
            if option=='http-user-agent':headers['userAgent']=value
            elif option in ('http-referrer','http-referer'):headers['referrer']=value
        elif line and not line.startswith('#'):
            if not info:continue
            attrs=m3u_attributes(info);label=m3u_label(info);info=None
            country=attrs.get('tvg-country','').upper()
            countries=set(re.split(r'[;,|\s]+',country))
            if us_ids is not None:
                if attrs.get('tvg-id','').split('@')[0] not in us_ids:continue
            elif 'US' not in countries and not (not country and attrs.get('group-title','').strip().casefold() in ('usa','united states','united states of america')):continue
            url,sep,options=line.partition('|')
            if sep:
                h=dict(urllib.parse.parse_qsl(options))
                headers.update({k:v for k,v in [('userAgent',h.get('User-Agent')),('referrer',h.get('Referer'))] if v})
            uri=urllib.parse.urlparse(url)
            if uri.scheme not in ('http','https') or not uri.hostname or uri.username:continue
            name=attrs.get('tvg-name') or re.sub(r'[\sⓈⒼⓎⓉ]+$','',label)
            if not name:continue
            entries.append({'externalId':attrs.get('tvg-id',''),'name':name,'logo':attrs.get('tvg-logo',''),
                'url':url,'userAgent':headers.get('userAgent',''),'referrer':headers.get('referrer','')})
    return entries

def merge_free_tv(items,extra,source="free-tv",namespace="free-tv",category=None):
    """Preserve canonical channel IDs/favorites; never join different stations by fuzzy names."""
    result=[dict(item,sources=list(item.get('sources',['iptv-org'])),categories=list(item.get('categories',[]))) for item in items]
    by_url=defaultdict(set);by_base=defaultdict(set);by_name=defaultdict(set)
    known={item['id']:item for item in result}
    for item in result:
        by_url[item['url']].add(item['id']);by_base[item['id'].split('@')[0]].add(item['id'])
        by_name[item['name'].casefold().strip()].add(item['id'])
    indexed={(item['id'],item['url'],item.get('userAgent',''),item.get('referrer','')):item for item in result}
    duplicate=0
    for entry in extra:
        external=entry['externalId'];canonical=None
        if len(by_url[entry['url']])==1:canonical=next(iter(by_url[entry['url']]))
        elif external in known:canonical=external
        elif len(by_base[external])==1:canonical=next(iter(by_base[external]))
        elif external+'@SD' in known:canonical=external+'@SD'
        elif len(by_name[entry['name'].casefold().strip()])==1:canonical=next(iter(by_name[entry['name'].casefold().strip()]))
        else:canonical=namespace+':'+(external or hashlib.sha256(entry['name'].casefold().encode()).hexdigest()[:20])
        parent=known.get(canonical,entry)
        item={'id':canonical,'channel':canonical.split('@')[0],'name':parent['name'],'logo':parent.get('logo') or entry['logo'],
            'url':entry['url'],'userAgent':entry['userAgent'],'referrer':entry['referrer'],'sources':[source],'categories':sorted(set(parent.get('categories',[]))|({category} if category else set()))}
        key=(canonical,item['url'],item['userAgent'],item['referrer'])
        if key in indexed:
            duplicate+=1
            if source not in indexed[key]['sources']:indexed[key]['sources'].append(source)
            indexed[key]['categories']=sorted(set(indexed[key].get('categories',[]))|set(item['categories']))
        else:
            result.append(item);indexed[key]=item;known.setdefault(canonical,item)
            by_url[item['url']].add(canonical);by_base[item['channel']].add(canonical);by_name[item['name'].casefold().strip()].add(canonical)
    return result,{'usEntries':len(extra),'duplicateStreamEntries':duplicate,'additionalCandidates':len(result)-len(items)}

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
        result.append({'id':cid+('@'+feed if feed else ''),'channel':cid,'name':((s.get('title') or us[cid]['name'])+(' ('+feed+')' if feed not in ('','HD','SD','UHD') and (s.get('title') or us[cid]['name'])==us[cid]['name'] else '')),
            'logo':logos.get(cid,''),'url':url,'userAgent':s.get('user_agent') or '', 'referrer':s.get('referrer') or '', 'categories':[c for c in (us[cid].get('categories') or []) if c in CATEGORIES]})
    extra=free_tv_us(fetch(FREE_TV,8_000_000)[0])
    if not extra:raise ValueError("Free-TV returned no US entries; preserving previous catalog")
    merged,summary=merge_free_tv(result,extra)
    def load_category(category):
        raw=fetch('https://iptv-org.github.io/iptv/categories/'+category+'.m3u',8_000_000)[0]
        return category,free_tv_us(raw,set(us))
    summaries={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        loaded=list(pool.map(load_category,CATEGORIES))
    for category,entries in loaded:
        merged,summaries[category]=merge_free_tv(merged,entries,'category:'+category,'iptv-category',category)
    return merged,summary,summaries

def scan(output,report,workers=24):
    started=time.time();items,free_summary,category_summaries=inputs();print('Candidate streams:',len(items),'Free-TV:',json.dumps(free_summary),flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        first=list(pool.map(probe,items))
        live=[i for i,x in enumerate(first) if x['state']=='live']
        print('Open-ended first samples:',len(live),flush=True)
        def again(i):
            remaining=12-(time.monotonic()-first[i]['sampled'])
            if remaining>0:time.sleep(remaining)
            return i,probe(items[i])
        second=dict(pool.map(again,live))
    channels={};reasons=Counter();source_counts=defaultdict(Counter);source_channels=defaultdict(set);free_only_streams=0
    for i,item in enumerate(items):
        a=first[i];b=second.get(i,{})
        passed=advancing(a,b)
        reason='verified' if passed else a['state'] if a['state']!='live' else ('frozen' if b.get('state')=='live' else b.get('state','error'))
        for source in item['sources']:
            source_counts[source]['candidateStreams']+=1;source_counts[source][reason]+=1
            if passed:source_channels[source].add(item['id'])
        if not passed:reasons[reason]+=1;continue
        if 'free-tv' in item['sources'] and 'iptv-org' not in item['sources']:free_only_streams+=1
        c=channels.setdefault(item['id'],{'id':item['id'],'name':item['name'],'logo':item['logo'],'streams':[],'categories':[]})
        c['streams'].append({k:item[k] for k in ['url','userAgent','referrer']})
        c['categories']=sorted(set(c['categories'])|set(item.get('categories',[])))
    entries=sorted(channels.values(),key=lambda c:c['name'].casefold())
    previous={}
    if os.path.exists(output):
        with open(output) as f:previous=json.load(f)
    if not entries or (len(previous.get('channels',[]))>40 and len(entries)<len(previous['channels'])*0.25):raise RuntimeError('Unusually empty scan; preserving last successful catalog')
    now=datetime.datetime.now(datetime.timezone.utc).isoformat()
    source_report={source:{**dict(values),'verifiedChannels':len(source_channels[source])} for source,values in source_counts.items()}
    source_report['free-tv'].update(free_summary)
    source_report['free-tv']['additionalVerifiedStreams']=free_only_streams
    source_report['free-tv']['additionalVerifiedChannels']=len(source_channels['free-tv']-source_channels['iptv-org'])
    for category,summary in category_summaries.items():
        source_report.setdefault('category:'+category,{'candidateStreams':0,'verifiedChannels':0}).update(summary)
    category_counts={category:sum(category in c['categories'] for c in entries) for category in CATEGORIES}
    counts={'candidateStreams':len(items),'verifiedChannels':len(entries),'verifiedStreams':sum(len(x['streams']) for x in entries),'excluded':dict(reasons),'seconds':round(time.time()-started),'sources':source_report,'categories':category_counts}
    doc={'schema':1,'generatedAt':now,'source':'iptv-org/api + 18 category playlists + Free-TV/IPTV','verification':'Two distinct advancing adaptive-live playlist snapshots; no finite clips. Availability can change.','counts':counts,'channels':entries}
    for path,data in [(output,doc),(report,{'generatedAt':now,**counts})]:
        with open(path+'.tmp','w') as f:json.dump(data,f,ensure_ascii=False,indent=2);f.write('\n')
        os.replace(path+'.tmp',path)
    print(json.dumps(counts),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',default='catalog.json');p.add_argument('--report',default='scan-report.json');p.add_argument('--workers',type=int,default=24)
    a=p.parse_args();scan(a.output,a.report,a.workers)
