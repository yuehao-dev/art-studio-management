"""Targeted account, deadline and enrollment-scope checks on the isolated test API."""
import base64
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import threading
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get('STUDIO_TEST_URL', 'http://127.0.0.1:18081/api')
assert BASE.startswith('http://127.0.0.1:18081/api'), 'Use the isolated test service only'
checks = []
tag = uuid.uuid4().hex[:8]
today = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()


def call(method, path, body=None, token='', expected=200, raw=None, mime=None):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    if body is not None:
        raw = json.dumps(body, ensure_ascii=False).encode('utf-8')
        headers['Content-Type'] = 'application/json'
    if mime:
        headers['Content-Type'] = mime
    req = urllib.request.Request(BASE + path, data=raw, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    if expected is not None:
        assert status == expected, f'{method} {path}: expected {expected}, received {status}'
        checks.append({'method': method, 'path': path, 'status': status})
    parsed = json.loads(data)
    return parsed.get('data') if status == 200 else status


def check(ok, message):
    assert ok, message
    checks.append({'check': message, 'passed': True})


def login(username, password='Review@2026'):
    return call('POST', '/auth/login', {'username': username, 'password': password})['token']


admin = login('admin', 'admin')


def post(path, data, token=admin, expected=200):
    return call('POST', path, data, token, expected)


def create_student(suffix):
    s = post('/students', {'name': '权限复核' + tag + suffix})
    name = 'qa_' + tag + suffix
    post('/users', {'username': name, 'password': 'Review@2026', 'displayName': suffix,
                    'role': 'STUDENT', 'studentId': s['id']})
    return s, name, login(name)


teacher = post('/teachers', {'name': '权限复核教师' + tag})
course = post('/courses', {'name': '权限复核课程' + tag, 'totalLessons': 8})
cls = post('/classes', {'name': '权限复核班' + tag, 'courseId': course['id'],
                         'teacherId': teacher['id'], 'capacity': 3})
target = post('/classes', {'name': '权限复核转入班' + tag, 'courseId': course['id'],
                            'teacherId': teacher['id'], 'capacity': 3})
student, username, token = create_student('a')
enrollment = post('/enrollments', {'studentId': student['id'], 'classId': cls['id']})
png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
boundary = 'Audit' + tag
raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="audit.png"\r\n'
       'Content-Type: image/png\r\n\r\n').encode() + png + f'\r\n--{boundary}--\r\n'.encode()
file = call('POST', '/files', token=token, raw=raw, mime='multipart/form-data; boundary=' + boundary)
expired = post('/tasks', {'classId': cls['id'], 'title': '已截止' + tag,
                            'dueDate': (today - dt.timedelta(days=1)).isoformat()})
current = post('/tasks', {'classId': cls['id'], 'title': '今日截止' + tag, 'dueDate': today.isoformat()})
open_task = post('/tasks', {'classId': cls['id'], 'title': '无限期' + tag})
post('/works', {'taskId': expired['id'], 'fileId': file['id'], 'title': '逾期作品'}, token, 409)
work = post('/works', {'taskId': current['id'], 'fileId': file['id'], 'title': '及时作品'}, token)
post(f"/works/{work['id']}/versions", {'fileId': file['id'], 'description': '当日修改'}, token)
tasks = {item['id']: item for item in call('GET', '/tasks', token=token)}
check(tasks[expired['id']]['submissionAllowed'] is False, 'Expired tasks remain readable but cannot be selected for submission')
check(tasks[current['id']]['submissionAllowed'] is True, 'Today deadline remains open through the whole Beijing date')

# Extending a task preserves its class and history while reopening valid submissions.
teacher_name = 'qa_teacher_' + tag
post('/users', {'username': teacher_name, 'password': 'Review@2026', 'displayName': '任课教师',
                'role': 'TEACHER', 'teacherId': teacher['id']})
teacher_token = login(teacher_name)
call('PUT', f"/tasks/{expired['id']}", {'dueDate': (today + dt.timedelta(days=1)).isoformat()}, teacher_token)
extended = post('/works', {'taskId': expired['id'], 'fileId': file['id'], 'title': '延期后的作品'}, token)
call('PUT', f"/tasks/{expired['id']}", {'dueDate': (today - dt.timedelta(days=1)).isoformat()}, teacher_token)
post(f"/works/{extended['id']}/versions", {'fileId': file['id']}, token, 409)
check(len(call('GET', f"/works/{extended['id']}/versions", token=token)) == 1,
      'Expired work history remains readable while additional versions are blocked')
call('PUT', f"/tasks/{expired['id']}", {'classId': target['id']}, teacher_token, 400)
call('PUT', f"/tasks/{expired['id']}", {'lessonId': 999999}, teacher_token, 400)
call('PUT', f"/tasks/{expired['id']}", {'title': '学生越权修改'}, token, 403)
other_teacher = login('teacher', 'Studio@2026')
call('PUT', f"/tasks/{expired['id']}", {'title': '无关教师越权'}, other_teacher, 403)

guardian_name = 'qa_' + tag + '_g'
post('/users', {'username': guardian_name, 'password': 'Review@2026', 'displayName': '监护人',
                'role': 'STUDENT', 'studentId': student['id'], 'readOnly': True})
guardian = login(guardian_name)
check(all(not item['submissionAllowed'] for item in call('GET', '/tasks', token=guardian)),
      'Guardian task list never advertises submission capability')
post('/works', {'taskId': open_task['id'], 'fileId': file['id'], 'title': '监护人越权'}, guardian, 403)

future_student, _, future_token = create_student('b')
post('/enrollments', {'studentId': future_student['id'], 'classId': cls['id'],
                       'effectiveDate': (today + dt.timedelta(days=20)).isoformat()})
classes = {item['id']: item for item in call('GET', '/classes', token=admin)}
check(classes[cls['id']]['studentCount'] == 1, 'Class headcount excludes enrollments that have not started')
post('/works', {'taskId': open_task['id'], 'fileId': file['id'], 'title': '未生效越权'}, future_token, 403)
post(f"/enrollments/{enrollment['id']}/transfer", {'classId': target['id'],
     'effectiveDate': (today + dt.timedelta(days=10)).isoformat(), 'reason': '预约转班'}, admin)
classes = {item['id']: item for item in call('GET', '/classes', token=admin)}
check(classes[cls['id']]['studentCount'] == 1 and classes[target['id']]['studentCount'] == 0,
      'Scheduled transfer still counts in the original class until its effective date')

third, _, _ = create_student('c')
third_enrollment = post('/enrollments', {'studentId': third['id'], 'classId': cls['id'],
    'effectiveDate': (today + dt.timedelta(days=5)).isoformat()})
post(f"/enrollments/{third_enrollment['id']}/transfer", {'classId': target['id'],
     'effectiveDate': (today + dt.timedelta(days=15)).isoformat(), 'reason': '预约转班'}, admin)
call('PUT', f"/classes/{cls['id']}", {'capacity': 1}, admin, 409)
check(True, 'Capacity reduction respects the peak of scheduled enrollment intervals')

# The development admin stays enabled; concurrency uses disposable isolated accounts.
admins = []
for suffix in ['x', 'y']:
    name = 'qa_admin_' + tag + suffix
    row = post('/users', {'username': name, 'password': 'Review@2026', 'displayName': suffix, 'role': 'ADMIN'})
    admins.append((row['id'], login(name)))
gate = threading.Barrier(2)


def disable_other(index):
    gate.wait(timeout=10)
    return call('PUT', f"/users/{admins[1-index][0]}", {'enabled': False}, admins[index][1], expected=None)


with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    outcomes = list(pool.map(disable_other, [0, 1]))
check(sum(isinstance(outcome, dict) for outcome in outcomes) == 1
      and sum(outcome in (401, 403) for outcome in outcomes if isinstance(outcome, int)) == 1,
      'Concurrent administrator revocation has one success and one clean authorization rejection')

# Case changes must not reset the failure counter for one existing account.
for _ in range(10):
    post('/auth/login', {'username': username, 'password': 'wrong'}, '', 401)
post('/auth/login', {'username': username.upper(), 'password': 'Review@2026'}, '', 429)
post('/students', {'name': '长' * 81}, admin, 400)
out = Path(__file__).resolve().parent.parent / 'work' / 'audit-access-results.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({'date': today.isoformat(), 'passed': len(checks), 'checks': checks},
                          ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(checks)} account, deadline, enrollment scope and capacity checks; evidence: {out.name}')
