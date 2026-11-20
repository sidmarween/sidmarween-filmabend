import json, urllib.request, re, time, unicodedata
Q="query($country:Country!,$language:Language!,$first:Int!,$filter:TitleFilter){popularTitles(country:$country,first:$first,filter:$filter){edges{node{objectId objectType content(country:$country,language:$language){title originalTitle originalReleaseYear posterUrl shortDescription genres{translation(language:$language)}}}}}}"
def norm(s): return re.sub(r'[^a-z0-9äöüß]','',s.lower())
def search(q):
    body=json.dumps({"query":Q,"variables":{"country":"DE","language":"de","first":8,"filter":{"searchQuery":q}}}).encode()
    req=urllib.request.Request("https://apis.justwatch.com/graphql",data=body,headers={"Content-Type":"application/json","User-Agent":"Mozilla/5.0"})
    d=json.loads(urllib.request.urlopen(req,timeout=20).read())
    return [e["node"] for e in d["data"]["popularTitles"]["edges"]]
out=[]; log=[]
for line in open('titles.txt',encoding='utf-8'):
    line=line.strip()
    if not line: continue
    title,year,typ=(line.split('|')+['',''])[:3]
    nodes=None
    for attempt in range(4):
        try: nodes=search(title); break
        except Exception as e:
            time.sleep(20*(attempt+1))
    if nodes is None:
        nodes=[]; log.append('ERR '+title)
    want='SHOW' if typ=='tv' else ('MOVIE' if typ=='movie' else None)
    def score(n):
        c=n['content']; s=0
        if norm(c['title'])==norm(title) or norm(c.get('originalTitle') or '')==norm(title): s+=10
        elif norm(title) in norm(c['title']) or norm(c['title']) in norm(title): s+=4
        if want and n['objectType']==want: s+=3
        if year and c.get('originalReleaseYear') and abs(int(year)-c['originalReleaseYear'])<=1: s+=5
        return s
    best=max(nodes,key=score) if nodes else None
    if best and score(best)>=7:
        c=best['content']
        poster=c.get('posterUrl') or ''
        if poster: poster='https://images.justwatch.com'+poster.replace('{profile}','s332').replace('{format}','jpg')
        out.append({"title":c['title'],"type":"tv" if best['objectType']=='SHOW' else "movie","id":2000000000+best['objectId'],
            "year":str(c.get('originalReleaseYear') or ''),"poster":poster,"overview":(c.get('shortDescription') or '')[:600],
            "genres":[g['translation'] for g in c.get('genres') or []],"query":title})
        log.append(f"OK  {title} -> {c['title']} ({c.get('originalReleaseYear')}) {best['objectType']} s={score(best)}")
    else:
        out.append({"title":title,"type":typ or "movie","year":year,"query":title})
        log.append(f"--  {title}" + (f" (best: {best['content']['title']} s={score(best)})" if best else ""))
    time.sleep(1.2)
json.dump(out,open('whatsapp_gesehen.json','w',encoding='utf-8'),ensure_ascii=False,indent=0)
open('jwlog.txt','w').write('\n'.join(log))
