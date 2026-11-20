for f in q1 q2; do echo "== $f" >> out.txt; curl -sS -X POST https://apis.justwatch.com/graphql -H "Content-Type: application/json" -A "Mozilla/5.0" --data @$f.json >> out.txt || true; echo >> out.txt; done
curl -sS "https://api.tvmaze.com/schedule?country=DE" | python3 -c "import json,sys;d=json.load(sys.stdin);print('tvDEtoday',len(d))" >> out.txt || true
