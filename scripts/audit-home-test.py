"""Verify role-specific homepage aggregates against authorized API lists."""
import datetime as dt, json, urllib.request, urllib.error
from pathlib import Path
BASE='http://127.0.0.1:18081/api'
checks=[]
def call(path, token='', body=None):
    headers={}
    if token: headers['Authorization']='Bearer '+token
    if body is not None: headers['Content-Type']='application/json'
    req=urllib.request.Request(BASE+path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req,timeout=20) as r:
        result=json.loads(r.read())
        assert result['code']==0
        return result['data']
def check(ok, label):
    assert ok,label
    checks.append(label)
for username,password in [('admin','admin'),('teacher','Studio@2026'),('student','Studio@2026'),('guardian','Studio@2026')]:
    session=call('/auth/login',body={'username':username,'password':password}); token=session['token']; user=session['user']
    home=call('/dashboard',token); students=call('/students',token); classes=call('/classes',token); works=call('/works',token); lessons=call('/lessons',token); leaves=call('/leaves',token)
    check(home['studentCount']==len(students),username+': student summary matches authorized records')
    check(home['classCount']==len(classes),username+': class summary matches authorized records')
    check(home['workCount']==len(works),username+': work summary matches authorized records')
    check(home['pendingLeaveCount']==sum(x['status']=='PENDING' for x in leaves),username+': pending leave summary matches authorized records')
    now=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).replace(tzinfo=None)
    expected=sorted([x for x in lessons if x['status']!='CANCELLED' and dt.datetime.fromisoformat(x['endTime'])>=now],key=lambda x:(x['startTime']))[:6]
    check([x['id'] for x in home['upcomingLessons']]==[x['id'] for x in expected],username+': upcoming list contains earliest authorized active courses')
    occurred=sum(x['occurred'] for x in home['progress']); completed=sum(x['completedOccurred'] for x in home['progress'])
    expected_rate=0 if not occurred else round(completed*100/occurred,1)
    check(abs(home['attendanceRate']-expected_rate)<0.11,username+': attendance aggregate uses ended courses only')
    if user['role']=='STUDENT':
        check(all(x['studentId']==user['studentId'] for x in home['progress']),username+': progress belongs only to bound student')
        check(all(x['studentId']==user['studentId'] for x in works),username+': works belong only to bound student')
report={'date':now.date().isoformat(),'passed':len(checks),'checks':checks}
out=Path(__file__).resolve().parent.parent/'docs/test-evidence/homepage-api.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':len(checks)}))
