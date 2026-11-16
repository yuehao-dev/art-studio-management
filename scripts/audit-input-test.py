import datetime as audit_dt
"""Input validation checks against the isolated development API only."""
import json, urllib.request, urllib.error
from pathlib import Path
BASE='http://127.0.0.1:18081/api'
checks=[]
def call(method,path,body=None,token='',status=200):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r: actual=r.status; payload=r.read()
    except urllib.error.HTTPError as e: actual=e.code; payload=e.read()
    assert actual==status, f'{method} {path}: {actual}, expected {status}'
    return json.loads(payload)
token=call('POST','/auth/login',{'username':'teacher','password':'Studio@2026'})['data']['token']
for title, method, path, body in [
    ('Reject fractional record identifier','POST','/tasks',{'classId':1.9,'title':'invalid fractional id'}),
    ('Reject overflowing record identifier','POST','/tasks',{'classId':9223372036854775808,'title':'invalid overflowing id'}),
    ('Reject malformed date suffix','GET','/lessons?from=2026-11-13unexpected',None),
    ('Reject non-existent calendar date','GET','/lessons?from=2026-02-30',None),
]:
    call(method,path,body,token,400); checks.append(title)
out=Path(__file__).resolve().parent.parent/'docs/test-evidence/input-audit.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({'date':audit_dt.date.today().isoformat(),'passed':len(checks),'checks':checks},indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':len(checks)}))
