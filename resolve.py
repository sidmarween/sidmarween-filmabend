import re, json, urllib.request, urllib.parse, html, sys
out=[]
def get(url, head=False):
    req=urllib.request.Request(url, headers={"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36","Accept-Language":"de-DE,de;q=0.9","Cookie":"SOCS=CAI; CONSENT=YES+1"})
    r=urllib.request.urlopen(req, timeout=20)
    return r.geturl(), r.read(400000).decode('utf-8','ignore')
for u in open('urls.txt').read().split():
    res=''
    try:
        if 'youtu' in u:
            final,body=get('https://www.youtube.com/oembed?format=json&url='+urllib.parse.quote(u, safe=''))
            res='YT: '+json.loads(body).get('title','')
        else:
            final,body=get(u)
            m=re.search(r'<title[^>]*>(.*?)</title>', body, re.S)
            og=re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]*)"', body)
            q=urllib.parse.parse_qs(urllib.parse.urlparse(final).query).get('q',[''])[0]
            res='Q: '+q if q else ''
            res+=' | T: '+html.unescape((og.group(1) if og else (m.group(1) if m else '')).strip())[:200]
            res+=' | F: '+final[:150]
    except Exception as e:
        res='ERR '+str(e)[:80]
    out.append(u+'\t'+res)
open('resolved.txt','w').write('\n'.join(out))
