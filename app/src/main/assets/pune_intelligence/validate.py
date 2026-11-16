import json,pathlib,re,collections
p=pathlib.Path(__file__).parent
def load(n):return json.loads((p/(n+'.json')).read_text())
files=list(p.glob('*.json'))
for f in files:json.loads(f.read_text())
for n in ['locations','postal_offices','commercial_projects','residential_projects','developers','metro_stations','search_aliases']:
 rows=load(n);ids=[x['id'] for x in rows];assert len(ids)==len(set(ids)),n
loc={x['id'] for x in load('locations')};dev={x['id'] for x in load('developers')}
for n in ['postal_offices','commercial_projects','residential_projects']:
 for r in load(n):assert r['location_id'] in loc,(n,r)
for n in ['commercial_projects','residential_projects']:
 for r in load(n):assert r['developer_id'] in dev,(n,r)
for r in load('postal_offices'):assert re.fullmatch(r'\d{6}',r['pin_code'])
assert not load('market_observations')
print('PASS:',len(files),'JSON files; foreign keys, unique IDs, PIN formats, no fabricated market observations')
