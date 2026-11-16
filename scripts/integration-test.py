"""Run against an isolated DEV database on port 18081. Creates uniquely named fixtures."""
import os, sys, json, urllib.request, urllib.error, uuid, datetime as dt, base64
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
BASE=os.environ.get('STUDIO_TEST_URL','http://127.0.0.1:18081/api')
assert ':18081/' in BASE or os.environ.get('STUDIO_ALLOW_TEST_WRITES')=='true', 'Use isolated test API'
results=[]
def request(method,path,body=None,token='',expected=200,raw=None,content_type=None):
    headers={}
    if token: headers['Authorization']='Bearer '+token
    data=raw
    if body is not None: data=json.dumps(body,ensure_ascii=False).encode();headers['Content-Type']='application/json'
    if content_type: headers['Content-Type']=content_type
    req=urllib.request.Request(BASE+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as r: status=r.status; content=r.read()
    except urllib.error.HTTPError as e: status=e.code;content=e.read()
    try: parsed=json.loads(content)
    except Exception: parsed=content
    assert status==expected, f'{method} {path}: expected {expected}, got {status}: {str(parsed)[:500]}'
    if isinstance(parsed,dict) and status==200: assert parsed.get('code')==0,parsed
    results.append({'method':method,'path':path,'expected':expected,'passed':True})
    return parsed.get('data') if isinstance(parsed,dict) and status==200 else parsed
def login(name,pwd=None):return request('POST','/auth/login',{'username':name,'password':pwd or ({'admin':'admin','test':'test'}.get(name,'Studio@2026'))})['token']
def get(path,token):return request('GET',path,token=token)
def post(path,body,token,expected=200):return request('POST',path,body,token,expected)
tag='验收'+uuid.uuid4().hex[:6]
today=dt.date.today()
def at(day,hour):return (today+dt.timedelta(days=day)).isoformat()+f'T{hour:02}:00:00'
A=login('admin');T=login('teacher');T2=login('teacher2');S=login('student');S2=login('student2');G=login('guardian')
request('GET','/students',expected=401)
post('/auth/login',{'username':'admin','password':'wrong'},'',401)
request('GET','/users',token=S,expected=403)
post('/students',{'name':'illegal'},T,403)
post('/feedback',{'content':'illegal'},G,403)
assert len(get('/students',S))==1 and len(get('/students',G))==1
assert {x['id'] for x in get('/students',T)}=={1,2}
for token in [A,T,S,G]:
    for path in ['/dashboard','/courses','/classes','/lessons','/progress','/tasks','/works','/resources','/notices','/feedback']:get(path,token)

# Seed past attendance: identical submit is idempotent, correction needs reason.
past=next(x for x in get('/lessons',A) if x['title']=='静物组合与构图')
parts=get(f"/lessons/{past['id']}/participants",T); part=next(x for x in parts if x['studentId']==1)
payload={'records':[{'participantId':part['id'],'status':part['attendanceStatus'],'remark':part['remark']}]}
post(f"/lessons/{past['id']}/attendance",payload,T)
post(f"/lessons/{past['id']}/attendance",payload,T)
post(f"/lessons/{past['id']}/attendance",{'records':[{'participantId':part['id'],'status':'PRESENT','remark':'更正'}]},T,400)
request('GET',f"/lessons/{past['id']}/participants",token=T2,expected=403)
assert len(get(f"/lessons/{past['id']}/participants",S))==1

# Completion of makeup counted once, duplicate blocked.
workshop=next(x for x in get('/classes',A) if x['name']=='素描补课工作坊')
target=post('/lessons',{'classId':workshop['id'],'teacherId':1,'roomId':1,'title':tag+'补课','startTime':at(-1,8),'endTime':at(-1,10)},A)
makeup=post('/makeups',{'originalParticipantId':part['id'],'targetLessonId':target['id'],'reason':'验收补课'},A)
post('/makeups',{'originalParticipantId':part['id'],'targetLessonId':target['id']},A,409)
mp=next(x for x in get(f"/lessons/{target['id']}/participants",T) if x['studentId']==1)
before=get('/progress',S)[0]['completed']
mpayload={'records':[{'participantId':mp['id'],'status':'PRESENT','remark':'完成补课'}]}
post(f"/lessons/{target['id']}/attendance",mpayload,T)
post(f"/lessons/{target['id']}/attendance",mpayload,T)
after=get('/progress',S)[0]['completed'];assert after==before+1
post('/makeups',{'originalParticipantId':part['id'],'targetLessonId':target['id']},A,409)
post(f"/makeups/{makeup['id']}/cancel",{'reason':'不应取消'},A,409)

# Independent catalog, concurrent capacity and schedule conflicts.
teacher=post('/teachers',{'name':tag+'教师'},A)
room=post('/rooms',{'name':tag+'教室','capacity':10},A)
course=post('/courses',{'name':tag+'课程','totalLessons':8},A)
cls=post('/classes',{'name':tag+'一班','courseId':course['id'],'teacherId':teacher['id'],'capacity':1},A)
students=[post('/students',{'name':tag+str(i)},A) for i in range(2)]
def compete(st):
    try:return post('/enrollments',{'studentId':st['id'],'classId':cls['id']},A)
    except AssertionError as e:
        assert 'got 409' in str(e),e
        results.append({'path':'/enrollments concurrent capacity','passed':True,'expected':409});return None
with ThreadPoolExecutor(max_workers=2) as pool: enrolled=list(pool.map(compete,students))
assert sum(x is not None for x in enrolled)==1
enrollment=next(x for x in enrolled if x);sid=enrollment['studentId']
post('/enrollments',{'studentId':sid,'classId':cls['id']},A,409)
lessonbody={'classId':cls['id'],'teacherId':teacher['id'],'roomId':room['id'],'title':tag+'原课','startTime':at(20,8),'endTime':at(20,10)}
lesson=post('/lessons',lessonbody,A)
post('/lessons',dict(lessonbody,title='冲突',startTime=at(20,9),endTime=at(20,11)),A,409)
post('/lessons',dict(lessonbody,title='无效时间',endTime=at(20,7)),A,400)
futurepart=get(f"/lessons/{lesson['id']}/participants",A)[0]
post(f"/lessons/{lesson['id']}/attendance",{'records':[{'participantId':futurepart['id'],'status':'PRESENT'}]},A,409)
post(f"/classes/{cls['id']}/close",{},A,409)

# Transfer leaves historical class/attendance intact and moves future membership.
cls2=post('/classes',{'name':tag+'二班','courseId':course['id'],'teacherId':teacher['id'],'capacity':5},A)
target2=post('/lessons',dict(lessonbody,classId=cls2['id'],title=tag+'转入课',startTime=at(20,10),endTime=at(20,12)),A)
post(f"/enrollments/{enrollment['id']}/transfer",{'classId':cls2['id'],'effectiveDate':today.isoformat(),'reason':'验收转班'},A)
assert not get(f"/lessons/{lesson['id']}/participants",A)
assert any(x['studentId']==sid for x in get(f"/lessons/{target2['id']}/participants",A))
post(f"/lessons/{lesson['id']}/cancel",{'reason':'验收停课'},A)
post(f"/classes/{cls['id']}/close",{},A)
post('/enrollments',{'studentId':students[0]['id'],'classId':cls['id']},A,409)

# Leave approval with scope and duplicate rejection.
future=next(x for x in get('/lessons',S) if x['status']=='SCHEDULED')
leave=post('/leaves',{'lessonId':future['id'],'reason':'验收请假'},S)
post('/leaves',{'lessonId':future['id'],'reason':'重复'},S,409)
post(f"/leaves/{leave['id']}/review",{'approved':True},T2,403)
post(f"/leaves/{leave['id']}/review",{'approved':True,'comment':'同意'},T)
post(f"/leaves/{leave['id']}/review",{'approved':True,'comment':'重复提交'},T)
shifted=dict((k,future[k]) for k in ['classId','teacherId','roomId','title','startTime','endTime'])
shifted['startTime']=(dt.datetime.fromisoformat(future['startTime'])+dt.timedelta(days=28)).isoformat()
shifted['endTime']=(dt.datetime.fromisoformat(future['endTime'])+dt.timedelta(days=28)).isoformat()
shifted['reason']='批准请假后调课，重置旧时间请假'
request('PUT',f"/lessons/{future['id']}",shifted,A)
assert next(x for x in get('/leaves',S) if x['id']==leave['id'])['status']=='CANCELLED'
assert get(f"/lessons/{future['id']}/participants",S)[0]['attendanceStatus'] is None
request('PUT','/classes/1',{'courseId':2},A,409)
request('PUT','/rooms/1',{'capacity':1},A,409)

# Upload, version history, reviews, and cross-user file protection.
png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
def upload(token,data=png,filename='test.png',expected=200):
    boundary='Studio'+uuid.uuid4().hex
    raw=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: image/png\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
    return request('POST','/files',token=token,expected=expected,raw=raw,content_type='multipart/form-data; boundary='+boundary)
file=upload(S);upload(S,b'not a valid image','fake.png',400)
request('GET',f"/files/{file['id']}/url",token=S2,expected=403)
request('GET',f"/files/{file['id']}/url",token=T,expected=403)
task=post('/tasks',{'classId':1,'title':tag+'作品任务','description':'测试修改过程'},T)
work=post('/works',{'taskId':task['id'],'fileId':file['id'],'title':tag+'作品','description':'第一版'},S)
post('/works',{'taskId':task['id'],'fileId':file['id'],'title':'重复'},S,409)
secondfile=upload(S)
post(f"/works/{work['id']}/versions",{'fileId':secondfile['id'],'description':'修改后'},S)
versions=get(f"/works/{work['id']}/versions",S);assert [x['versionNo'] for x in versions]==[2,1]
post(f"/works/{work['id']}/reviews",{'versionId':versions[0]['id'],'content':'构图更清晰','score':90},T)
post(f"/works/{work['id']}/reviews",{'versionId':versions[0]['id'],'content':'越权'},T2,403)
request('GET',f"/works/{work['id']}/versions",token=S2,expected=403)
get(f"/files/{file['id']}/url",G);get(f"/files/{file['id']}/url",T)
request('GET',f"/files/{file['id']}/url",token=T2,expected=403)
ticket=get(f"/files/{file['id']}/url",S)['url'];request('GET',ticket.removeprefix('/api'))
request('GET',f"/files/{secondfile['id']}/content?ticket="+ticket.split('ticket=')[1],expected=403)

# Resources, notices and feedback are real persisted records.
tf=upload(T);resource=post('/resources',{'classId':1,'fileId':tf['id'],'title':tag+'资料'},T)
get(f"/files/{tf['id']}/url",S)
request('GET',f"/files/{tf['id']}/url",token=T2,expected=403)
post('/notices',{'title':'教师不能全局发布','content':'测试'},T,403)
notice=post('/notices',{'classId':1,'title':tag+'通知','content':'下次课准备铅笔'},T)
post(f"/notices/{notice['id']}/read",{},S);post(f"/notices/{notice['id']}/read",{},S)
assert next(x for x in get('/notices',S) if x['id']==notice['id'])['read']
feedback=post('/feedback',{'content':tag+'希望增加练习'},S)
request('PUT',f"/feedback/{feedback['id']}/reply",{'reply':'已安排'},A)
assert next(x for x in get('/feedback',S) if x['id']==feedback['id'])['reply']=='已安排'
assert all(x['userId']!=3 for x in get('/feedback',S2))

# Disabled account/session invalidation and password change.
uname='qa_'+uuid.uuid4().hex[:8]
user=post('/users',{'username':uname,'password':'Test@2026','displayName':tag,'role':'STUDENT','studentId':sid},A)
u=login(uname,'Test@2026')
request('PUT',f"/users/{user['id']}",{'enabled':False},A)
request('GET','/auth/me',token=u,expected=401)
request('PUT',f"/users/{user['id']}",{'enabled':True},A)
u=login(uname,'Test@2026')
request('PUT','/auth/password',{'oldPassword':'Test@2026','newPassword':'Next@2026'},u)
request('GET','/auth/me',token=u,expected=401)
u=login(uname,'Next@2026');post('/auth/logout',{},u);request('GET','/auth/me',token=u,expected=401)
logs=get('/audit-logs',A);assert any(x['action']=='TRANSFER' for x in logs) and any(x['action']=='REVIEW_WORK' for x in logs)
out=Path(__file__).resolve().parent.parent/'work'/'integration-results.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({'date':dt.datetime.now().isoformat(),'base':BASE,'passed':len(results),'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
print(f'PASS: {len(results)} HTTP assertions, transfer, concurrent capacity, attendance/makeup, file access, works, sessions. Report: {out}')
