"""Leave/makeup/statistics edge cases; writes only to the isolated local API on 18081."""
import argparse
import datetime as dt
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--expect-old-rate', action='store_true')
args = parser.parse_args()
base = 'http://127.0.0.1:18081/api'
checks = []
tag = 'leave_audit_' + uuid.uuid4().hex[:8]
now = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).replace(tzinfo=None, microsecond=0)


def call(method, path, body=None, token='', status=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, method=method, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            actual, payload = response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        actual, payload = error.code, json.loads(error.read())
    assert actual == status, f'{method} {path}: expected {status}, received {actual}'
    if actual == 200:
        assert payload['code'] == 0, 'Unexpected application error'
        return payload['data']
    return payload


def check(ok, name):
    assert ok, name
    checks.append(name)


admin = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin'})['token']
def post(path, body, token=admin, status=200):
    return call('POST', path, body, token, status)
def get(path, token=admin):
    return call('GET', path, token=token)

teacher = post('/teachers', {'name': tag})
room = post('/rooms', {'name': tag, 'capacity': 10})
course = post('/courses', {'name': tag, 'totalLessons': 12})
cls = post('/classes', {'name': tag, 'courseId': course['id'], 'teacherId': teacher['id'], 'capacity': 10})
student = post('/students', {'name': tag})
post('/enrollments', {'studentId': student['id'], 'classId': cls['id'],
                      'effectiveDate': (now.date() - dt.timedelta(days=5)).isoformat()})
username = 'la_' + uuid.uuid4().hex[:8]
post('/users', {'username': username, 'password': 'Audit@2026', 'displayName': tag,
               'role': 'STUDENT', 'studentId': student['id']})
learner = call('POST', '/auth/login', {'username': username, 'password': 'Audit@2026'})['token']


def lesson(class_id, start, end):
    return post('/lessons', {'classId': class_id, 'teacherId': teacher['id'], 'roomId': room['id'],
                            'title': tag, 'startTime': start.isoformat(), 'endTime': end.isoformat()})


past = lesson(cls['id'], now - dt.timedelta(days=2, hours=2), now - dt.timedelta(days=2))
ongoing = lesson(cls['id'], now - dt.timedelta(minutes=10), now + dt.timedelta(hours=1))
past_part = get(f"/lessons/{past['id']}/participants")[0]
current_part = get(f"/lessons/{ongoing['id']}/participants")[0]
post(f"/lessons/{past['id']}/attendance", {'records': [{'participantId': past_part['id'], 'status': 'ABSENT'}]})
post(f"/lessons/{ongoing['id']}/attendance", {'records': [{'participantId': current_part['id'], 'status': 'PRESENT'}]})
progress = get(f"/progress?studentId={student['id']}")[0]
check(progress['expected'] == 2 and progress['occurred'] == 1 and progress['completed'] == 1,
      'Ongoing attendance remains separate from ended lesson count')
observed_rate = progress['attendanceRate']
if args.expect_old_rate:
    check(observed_rate == 100, 'Reproduced old defect: ongoing attendance inflated ended-lesson rate to 100%')
else:
    check(observed_rate == 0, 'Ongoing attendance does not inflate ended-lesson attendance rate')
    check(progress['completedOccurred'] == 0, 'Completed ended-lesson numerator excludes ongoing lesson')
    check(get('/dashboard', learner)['attendanceRate'] == 0, 'Dashboard uses the same ended-lesson numerator')
    call('GET', '/progress?from=2026-12-01&to=2026-11-01', token=admin, status=400)
    checks.append('Reversed statistics date range rejected')

future_start = (now + dt.timedelta(days=3)).replace(hour=9, minute=0, second=0)
future = lesson(cls['id'], future_start, future_start + dt.timedelta(hours=1))
leave = post('/leaves', {'lessonId': future['id'], 'reason': 'audit'}, learner)
post(f"/leaves/{leave['id']}/review", {'approved': 'true'}, status=400)
checks.append('Leave approval requires an actual Boolean')
post(f"/leaves/{leave['id']}/review", {'approved': False, 'comment': 'provide details'})
reapplied = post('/leaves', {'lessonId': future['id'], 'reason': 'audit details'}, learner)
check(reapplied['id'] == leave['id'] and reapplied['status'] == 'PENDING',
      'Rejected leave can be resubmitted without duplicate participant rows')
approved = post(f"/leaves/{leave['id']}/review", {'approved': True})
check(approved['status'] == 'APPROVED', 'Resubmitted leave can be approved')
post(f"/leaves/{leave['id']}/review", {'approved': True})
check(len(get(f"/lessons/{future['id']}/participants")) == 1,
      'Repeated approval does not duplicate participant records')
post(f"/leaves/{leave['id']}/review", {'approved': False}, status=409)
checks.append('Approved leave cannot be switched to rejected by a second review')
post('/leaves', {'lessonId': future['id'], 'reason': 'duplicate'}, learner, 409)
checks.append('Duplicate active leave is rejected')
source_part = get(f"/lessons/{future['id']}/participants")[0]
check(source_part['attendanceStatus'] == 'LEAVE', 'Approved leave creates a LEAVE attendance record')

target_class = post('/classes', {'name': tag + '_makeup', 'courseId': course['id'],
                                 'teacherId': teacher['id'], 'capacity': 10})
before = lesson(target_class['id'], future_start - dt.timedelta(hours=2), future_start - dt.timedelta(hours=1))
post('/makeups', {'originalParticipantId': source_part['id'], 'targetLessonId': before['id']}, status=409)
checks.append('Makeup before the original lesson ends is rejected')
target = lesson(target_class['id'], future_start + dt.timedelta(hours=1), future_start + dt.timedelta(hours=2))
makeup = post('/makeups', {'originalParticipantId': source_part['id'], 'targetLessonId': target['id']})
check(makeup['status'] == 'ARRANGED', 'Makeup can start exactly when the original lesson ends')
post('/makeups', {'originalParticipantId': source_part['id'], 'targetLessonId': target['id']}, status=409)
checks.append('Duplicate active makeup is rejected')
post('/leaves', {'lessonId': target['id'], 'reason': 'nested makeup'}, learner, 409)
checks.append('Makeup participants cannot create nested leave/makeup chains')
post(f"/makeups/{makeup['id']}/cancel", {'reason': 'change plan'})
check(not get(f"/lessons/{target['id']}/participants"), 'Cancelled makeup removes the active target seat')
new_makeup = post('/makeups', {'originalParticipantId': source_part['id'], 'targetLessonId': target['id']})
check(new_makeup['id'] != makeup['id'] and new_makeup['targetParticipantId'] == makeup['targetParticipantId'],
      'Rearranging a cancelled makeup safely reuses the unique lesson/student row')
check(len(get(f"/lessons/{target['id']}/participants")) == 1, 'Rearranged makeup occupies exactly one seat')
post(f"/makeups/{new_makeup['id']}/cancel", {'reason': 'test cleanup'})
post('/makeups', {'originalParticipantId': current_part['id'], 'targetLessonId': target['id']}, status=409)
checks.append('An attended original lesson cannot request makeup')

report = {'date': now.isoformat(), 'mode': 'before-fix' if args.expect_old_rate else 'after-fix',
          'passed': len(checks), 'observedAttendanceRate': observed_rate, 'checks': checks}
name = 'leave-audit-before.json' if args.expect_old_rate else 'leave-audit-after.json'
out = Path(__file__).resolve().parent.parent / 'docs/test-evidence' / name
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'passed': len(checks), 'observedAttendanceRate': observed_rate, 'report': str(out)}, ensure_ascii=False))
