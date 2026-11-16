"""Scheduling boundary regressions. Only writes to the isolated local test API."""
import datetime as dt
import json
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
BASE = 'http://127.0.0.1:18081/api'
results = []
token = ''

def call(method, path, body=None):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    payload = None
    if body is not None:
        headers['Content-Type'] = 'application/json'
        payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
    request = urllib.request.Request(BASE + path, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status, data = response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        status, data = error.code, json.loads(error.read())
    return status, data.get('data'), data.get('message', '')

def ok(method, path, body=None):
    status, data, message = call(method, path, body)
    if status != 200:
        raise AssertionError(f'Fixture setup failed: {method} {path}: {status} {message}')
    return data

def check(name, actual, expected):
    results.append({'name': name, 'passed': actual == expected, 'actual': actual, 'expected': expected})

today = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
tag = '边界审查' + uuid.uuid4().hex[:7]
token = ok('POST', '/auth/login', {'username': 'admin', 'password': 'admin'})['token']
teacher = ok('POST', '/teachers', {'name': tag + '教师'})
room = ok('POST', '/rooms', {'name': tag + '教室', 'capacity': 20})
course = ok('POST', '/courses', {'name': tag + '课程', 'totalLessons': 12})

def cls(suffix, capacity=10):
    return ok('POST', '/classes', {'name': tag + suffix, 'teacherId': teacher['id'], 'courseId': course['id'], 'capacity': capacity})

def student(suffix):
    return ok('POST', '/students', {'name': tag + suffix})

def enroll(s, c, days):
    return ok('POST', '/enrollments', {'studentId': s['id'], 'classId': c['id'], 'effectiveDate': (today + dt.timedelta(days=days)).isoformat()})

def lesson(c, suffix, days, hour=0):
    start = dt.datetime.combine(today + dt.timedelta(days=days), dt.time(hour))
    return ok('POST', '/lessons', {'classId': c['id'], 'teacherId': teacher['id'], 'roomId': room['id'], 'title': tag + suffix, 'startTime': start.isoformat(), 'endTime': (start + dt.timedelta(hours=1)).isoformat()})

# Historical absence remains a teaching record and must not be removed by cancellation.
c1, s1 = cls('历史缺勤'), student('缺勤学员')
enroll(s1, c1, -5)
l1 = lesson(c1, '已缺勤', -2)
p1 = ok('GET', f"/lessons/{l1['id']}/participants")[0]
ok('POST', f"/lessons/{l1['id']}/attendance", {'records': [{'participantId': p1['id'], 'status': 'ABSENT'}]})
check('Cannot cancel a lesson with recorded absence', call('POST', f"/lessons/{l1['id']}/cancel", {'reason': '边界验证'})[0], 409)

# A date-based transfer cannot rewrite today's lesson once it has started.
c2, target2, s2 = cls('当日转出'), cls('当日转入'), student('转班学员')
e2 = enroll(s2, c2, -5)
l2 = lesson(c2, '今日已开始', 0)
check('Transfer cannot remove already-started same-day participants', call('POST', f"/enrollments/{e2['id']}/transfer", {'classId': target2['id'], 'effectiveDate': today.isoformat(), 'reason': '边界验证'})[0], 409)
check('Started lesson retains its original participant snapshot', len(ok('GET', f"/lessons/{l2['id']}/participants")), 1)

# A new enrollment does not retroactively add the new student to an earlier class today.
c3, s3 = cls('新报名'), student('晚报名学员')
l3 = lesson(c3, '已结束空课', -1, 2)
enroll(s3, c3, -1)
check('New enrollment does not rewrite an already-started lesson', len(ok('GET', f"/lessons/{l3['id']}/participants")), 0)

# A future transfer gives a half-open enrollment interval; non-overlapping members share capacity.
c4, target4 = cls('错峰容量', 2), cls('预约转入')
a, b, c = student('时段一'), student('时段二'), student('全时段')
ea = enroll(a, c4, 10)
ok('POST', f"/enrollments/{ea['id']}/transfer", {'classId': target4['id'], 'effectiveDate': (today + dt.timedelta(days=20)).isoformat(), 'reason': '时段一结束'})
enroll(b, c4, 20)
check('Non-overlapping future enrollment intervals do not overcount capacity', call('POST', '/enrollments', {'studentId': c['id'], 'classId': c4['id'], 'effectiveDate': today.isoformat()})[0], 200)

# Invalid batches must roll back earlier records in the same attendance submission.
c5, s5a, s5b = cls('考勤事务'), student('事务一'), student('事务二')
enroll(s5a, c5, -5)
enroll(s5b, c5, -5)
l5 = lesson(c5, '批次考勤', -1, 4)
parts5 = ok('GET', f"/lessons/{l5['id']}/participants")
check('A mixed attendance batch rejects a participant from another lesson', call('POST', f"/lessons/{l5['id']}/attendance", {'records': [
    {'participantId': parts5[0]['id'], 'status': 'PRESENT'},
    {'participantId': p1['id'], 'status': 'PRESENT'}]})[0], 400)
check('Rejected attendance batch leaves all current participants unchanged', all(p['attendanceStatus'] is None for p in ok('GET', f"/lessons/{l5['id']}/participants")), True)
check('Duplicate participant in one attendance batch is rejected', call('POST', f"/lessons/{l5['id']}/attendance", {'records': [
    {'participantId': parts5[0]['id'], 'status': 'PRESENT'},
    {'participantId': parts5[0]['id'], 'status': 'ABSENT'}]})[0], 400)
check('Rejected duplicate batch rolls back its first record', all(p['attendanceStatus'] is None for p in ok('GET', f"/lessons/{l5['id']}/participants")), True)

# Adjacent periods may share resources; true overlap is rejected.
l6 = lesson(c5, '相邻课一', 25, 8)
check('A lesson may start exactly when the previous lesson ends', call('POST', '/lessons', {
    'classId': c5['id'], 'teacherId': teacher['id'], 'roomId': room['id'], 'title': tag + '相邻课二',
    'startTime': (dt.datetime.combine(today + dt.timedelta(days=25), dt.time(9))).isoformat(),
    'endTime': (dt.datetime.combine(today + dt.timedelta(days=25), dt.time(10))).isoformat()})[0], 200)
check('Overlapping lessons sharing the same teacher and room are rejected', call('POST', '/lessons', {
    'classId': c5['id'], 'teacherId': teacher['id'], 'roomId': room['id'], 'title': tag + '交叉冲突',
    'startTime': (dt.datetime.combine(today + dt.timedelta(days=25), dt.time(8, 30))).isoformat(),
    'endTime': (dt.datetime.combine(today + dt.timedelta(days=25), dt.time(9, 30))).isoformat()})[0], 409)

# Approved leave creates provisional LEAVE attendance and must not block a legitimate future transfer.
c7, target7, s7 = cls('未来请假转出'), cls('未来请假转入'), student('已请假转班')
e7 = enroll(s7, c7, 0)
l7 = lesson(c7, '未来已批准请假', 30, 12)
username7 = 'sched_' + uuid.uuid4().hex[:12]
ok('POST', '/users', {'username': username7, 'password': 'Test@2026', 'displayName': tag + '学生', 'role': 'STUDENT', 'studentId': s7['id']})
admin_token = token
token = ok('POST', '/auth/login', {'username': username7, 'password': 'Test@2026'})['token']
leave7 = ok('POST', '/leaves', {'lessonId': l7['id'], 'reason': '家庭安排'})
token = admin_token
ok('POST', f"/leaves/{leave7['id']}/review", {'approved': True, 'comment': '同意'})
check('Approved future leave does not block a valid class transfer', call('POST', f"/enrollments/{e7['id']}/transfer", {
    'classId': target7['id'], 'effectiveDate': today.isoformat(), 'reason': '转班应撤销未来请假'})[0], 200)
check('Transfer cancels the obsolete approved leave', next(r for r in ok('GET', '/leaves') if r['id'] == leave7['id'])['status'], 'CANCELLED')

report = {'date': today.isoformat(), 'base': BASE, 'passed': sum(x['passed'] for x in results), 'total': len(results), 'results': results}
name = 'audit-schedule-results.json' if all(x['passed'] for x in results) else 'audit-schedule-before.json'
output = Path(__file__).resolve().parent.parent / 'work' / name
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
sys.exit(0 if all(x['passed'] for x in results) else 1)
