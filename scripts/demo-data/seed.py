"""Explicit, additive teaching fixtures. Default: transaction preview and rollback.

See docs/演示数据.md. Credentials are read only from environment variables.
Never run automatically on application startup. Existing IDs are not assumed.
"""
import argparse
from collections import defaultdict
from datetime import date, datetime, time, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import random
import urllib.request
import uuid

from artwork import draw_exercise

BATCH = 'realistic-teaching-v1'
TABLES = ('students teachers courses rooms classes enrollments transfers lessons '
          'lesson_participants attendance leaves makeups makeup_suggestions tasks '
          'works work_versions reviews resources notices feedback').split()


class Seeder:
    def __init__(self, conn, today, apply=False, api='', output=Path('work/demo-data')):
        self.conn, self.today, self.apply = conn, today, apply
        self.api, self.output = api.rstrip('/'), Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.ids = defaultdict(list)
        self.renames = []
        self.rng = random.Random(20261119)
        self.token = None
        self.file_manifest = []
        self.admin = None

    def query(self, sql, args=()):
        with self.conn.cursor() as c:
            c.execute(sql, args)
            return c.fetchall()

    def execute(self, sql, args=()):
        with self.conn.cursor() as c:
            c.execute(sql, args)
            return c.lastrowid

    def add(self, table, **row):
        # Table/column names are internal constants; all values are parameterized.
        keys = ','.join('`'+k+'`' for k in row)
        ident = self.execute(f'INSERT INTO `{table}` ({keys}) VALUES ({",".join(["%s"]*len(row))})', tuple(row.values()))
        self.ids[table].append(ident)
        return ident

    def stamp(self, offset, hour=10):
        return datetime.combine(self.today+timedelta(days=offset), time(hour))

    def rename_originals(self):
        for table, old, new in [('students', '林小满', '林悦'), ('students', '周予安', '周浩'),
                                ('students', '陈知夏', '陈静'), ('teachers', '许青禾', '许敏'),
                                ('teachers', '沈砚', '沈涛')]:
            rows = self.query(f'SELECT id FROM {table} WHERE name=%s', (old,))
            for row in rows:
                ref = 'student_id' if table == 'students' else 'teacher_id'
                self.execute(f'UPDATE {table} SET name=%s WHERE id=%s AND name=%s', (new, row['id'], old))
                self.execute(f'UPDATE users SET display_name=%s WHERE {ref}=%s AND display_name=%s', (new, row['id'], old))
                if table == 'students':
                    self.execute("UPDATE students SET phone='',guardian_phone='' WHERE id=%s AND phone LIKE '1380000000_' AND guardian_phone LIKE '1390000000_'", (row['id'],))
                    self.execute("UPDATE students SET notes='按课表固定到课，持续练习观察与造型。' WHERE id=%s AND notes IN ('示例学员','示例学员，练习素描与色彩')", (row['id'],))
                self.renames.append({'table': table, 'id': row['id'], 'before': old, 'after': new})

    def file(self, label, category, seed, revision, when, purpose='works'):
        filename = f'{label}-v{revision}.png'
        if not self.apply:
            # Rolled back preview only; no writes to local storage/OSS.
            return self.add('files', owner_id=self.admin, original_name=filename,
                            storage_key=f'studio/preview/{uuid.uuid4().hex}.png', content_type='image/png',
                            size=1, provider='local', created_at=when)
        path = self.output / 'assets' / filename
        draw_exercise(path, category, seed, revision)
        if not self.token:
            body = json.dumps({'username': os.environ['DEMO_API_USERNAME'], 'password': os.environ['DEMO_API_PASSWORD']}).encode()
            req = urllib.request.Request(self.api+'/auth/login', body, {'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=30) as res:
                self.token = json.load(res)['data']['token']
        boundary = uuid.uuid4().hex
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n{purpose}\r\n'
                   f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                   'Content-Type: image/png\r\n\r\n').encode()+path.read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
        req = urllib.request.Request(self.api+'/files', payload, {'Authorization': 'Bearer '+self.token,
                                      'Content-Type': 'multipart/form-data; boundary='+boundary})
        with urllib.request.urlopen(req, timeout=60) as res:
            data = json.load(res)
        if data.get('code') != 0:
            raise RuntimeError('Upload failed; inspect server logs without publishing secrets')
        ident = data['data']['id']
        self.file_manifest.append({'id': ident, 'name': filename, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        (self.output/'uploaded-files.json').write_text(json.dumps(self.file_manifest, ensure_ascii=False, indent=2), encoding='utf-8')
        # This is a fictional historical fixture; make metadata agree with the submission timeline.
        self.execute('UPDATE files SET created_at=%s WHERE id=%s AND owner_id=%s', (when, ident, self.admin))
        return ident

    def run(self):
        self.conn.begin()
        self.query('SELECT id FROM schedule_guard WHERE id=1 FOR UPDATE')
        if self.query("SELECT id FROM audit_logs WHERE action='DEMO_DATA_IMPORT' AND target_id=%s", (BATCH,)):
            self.conn.rollback()
            return {'status': 'already_imported', 'batch': BATCH}
        admin = self.query("SELECT id FROM users WHERE username='admin' AND role='ADMIN'")
        if not admin:
            raise ValueError('Create an administrator before importing demo data')
        self.admin = admin[0]['id']
        self.rename_originals()
        classes = self.query('SELECT * FROM classes ORDER BY id')
        # Find the original classes by business names, not numeric primary keys.
        sketch = next((x for x in classes if x['name']=='素描启蒙 A 班'), None)
        water = next((x for x in classes if x['name']=='周末水彩班'), None)
        if not sketch or not water:
            raise ValueError('This fixture extends the two supplied demo classes; no data has been committed')
        for c in (sketch, water):
            count = self.query("SELECT COUNT(*) n FROM enrollments WHERE class_id=%s AND status='ACTIVE'", (c['id'],))[0]['n']
            if count > 3:
                raise ValueError('Existing demo classes have been expanded manually; preview and adapt this fixture first')

        start = self.today-timedelta(days=75)
        created = datetime.combine(start-timedelta(days=15), time(10))
        new_teachers = [self.add('teachers', name=n, phone='', specialty=s, created_at=created)
                        for n, s in [('李娜', '少儿创意绘画 / 综合材料'), ('王志强', '国画 / 书法'), ('刘洋', '油画 / 色彩基础')]]
        rooms = [self.add('rooms', name=n, capacity=cap, location=loc, created_at=created)
                 for n, cap, loc in [('三号教室', 12, '二楼东侧，多功能绘画教室'), ('四号教室', 10, '二楼南侧，国画书法教室')]]
        oldrooms = self.query('SELECT id FROM rooms ORDER BY id LIMIT 2')
        course_ids = [self.add('courses', name=n, description=d, total_lessons=hours, created_at=created)
                      for n, d, hours in [('少儿创意绘画', '面向7—9岁学员，练习观察、色彩表达与综合材料创作。', 16),
                                         ('素描进阶', '面向有素描基础的学员，学习透视、结构与完整静物塑造。', 16),
                                         ('国画基础', '从执笔、用墨和花鸟临摹入门，建立笔墨习惯。', 16),
                                         ('成人油画入门', '面向零基础成人，学习调色、笔触与风景写生。', 12)]]
        specs = [
            (sketch, 'sketch', 5, 14, oldrooms[0]['id'], 11),
            (water, 'water', 6, 10, oldrooms[1]['id'], 12),
        ]
        for name, course, teacher, category, weekday, hour, room, age in [
            ('少儿创意绘画 · 周六上午班', course_ids[0], new_teachers[0], 'creative', 5, 9, rooms[0], 8),
            ('素描进阶 · 周日上午班', course_ids[1], sketch['teacher_id'], 'advanced', 6, 9, oldrooms[0]['id'], 15),
            ('水彩提高 · 周日下午班', water['course_id'], water['teacher_id'], 'water', 6, 14, oldrooms[1]['id'], 13),
            ('国画入门 · 周六下午班', course_ids[2], new_teachers[1], 'ink', 5, 14, rooms[1], 11),
            ('成人油画 · 周三晚班', course_ids[3], new_teachers[2], 'oil', 2, 19, rooms[0], 30),
        ]:
            cid = self.add('classes', name=name, course_id=course, teacher_id=teacher, capacity=10,
                           status='ACTIVE', created_at=created)
            specs.append(({'id': cid, 'teacher_id': teacher, 'course_id': course, 'name': name}, category, weekday, hour, room, age))
        groups = [
            [('张晨','男'),('李欣','女'),('王浩宇','男'),('刘佳怡','女'),('赵磊','男'),('黄雨桐','女'),('吴俊杰','男'),('孙悦','女')],
            [('陈睿','男'),('周婷婷','女'),('杨帆','男'),('郑思雨','女'),('徐浩','男'),('胡欣然','女'),('何嘉乐','男')],
            [('王乐乐','女'),('李嘉诚','男'),('张可欣','女'),('陈一航','男'),('刘雨萱','女'),('赵梓涵','女'),('吴泽宇','男'),('周佳宁','女')],
            [('黄子轩','男'),('徐静怡','女'),('孙博文','男'),('马一鸣','男'),('朱悦','女'),('胡思源','男'),('郭嘉琪','女'),('何宇航','男')],
            [('林佳','女'),('高铭','男'),('罗欣','女'),('梁宇','男'),('宋雨晴','女'),('郑欣怡','女'),('邓嘉豪','男'),('冯思涵','女')],
            [('谢雨欣','女'),('唐浩然','男'),('韩佳乐','女'),('曹文轩','男'),('许静','女'),('袁一帆','男'),('彭佳慧','女'),('曾子墨','男')],
            [('张伟','男'),('王丽','女'),('李强','男'),('刘敏','女'),('陈洁','女'),('赵斌','男'),('杨静','女'),('周建华','男')],
        ]
        notes = ['观察比较仔细，鼓励在起稿阶段先概括大形。', '喜欢色彩练习，注意保持画面整体关系。',
                 '有半年绘画基础，希望加强结构和透视。', '每周固定到课，请提前一天通知课程变更。',
                 '学习态度认真，作业建议分两次完成。', '刚开始系统学习，以培养观察习惯为主。']
        members = {}
        enrollments = {}
        for i, (cls, cat, wd, hour, room, age) in enumerate(specs):
            cid = cls['id']
            old = self.query("SELECT student_id FROM enrollments WHERE class_id=%s AND status='ACTIVE'", (cid,))
            members[cid] = [x['student_id'] for x in old]
            for j, (name, gender) in enumerate(groups[i]):
                year = self.today.year-age-(j%3 if age<20 else j*3)
                birthday = date(year, 1+(j*3+i)%12, 3+(j*5+i)%25)
                sid = self.add('students', name=name, gender=gender, birthday=birthday, phone='',
                               guardian_name='' if age>=20 else name[0]+('女士' if j%2 else '先生'),
                               guardian_phone='', notes=notes[(i+j)%len(notes)], created_at=created+timedelta(days=j))
                members[cid].append(sid)
                enrollments[sid] = self.add('enrollments', student_id=sid, class_id=cid, status='ACTIVE',
                                           effective_date=start, created_at=created+timedelta(days=j))
        # A simulated transfer: final sketch member moved to advanced class two weeks ago.
        source, target = specs[0][0]['id'], specs[3][0]['id']
        transfer_student = members[source][-1]
        transfer_day = self.today-timedelta(days=14)
        self.execute("UPDATE enrollments SET status='TRANSFERRED',end_date=%s WHERE id=%s", (transfer_day, enrollments[transfer_student]))
        new_enrollment = self.add('enrollments', student_id=transfer_student, class_id=target, status='ACTIVE',
                                  effective_date=transfer_day, created_at=datetime.combine(transfer_day-timedelta(days=2), time(10)))
        self.add('transfers', enrollment_id=enrollments[transfer_student], new_enrollment_id=new_enrollment,
                 student_id=transfer_student, from_class_id=source, to_class_id=target, effective_date=transfer_day,
                 reason='已完成基础形体训练，经教师评估转入进阶班，周日上午的时间也更合适。', created_by=self.admin,
                 created_at=datetime.combine(transfer_day-timedelta(days=2), time(10)))
        members[target].append(transfer_student)

        topics = {
            'sketch': ['石膏几何体的比例', '单个陶罐的明暗', '水果与衬布组合', '方体与圆柱的透视', '静物组合的构图', '黑白灰层次练习', '画面整体调整', '阶段习作与讲评', '结构线与边缘虚实'],
            'advanced': ['组合静物结构分析', '衬布褶皱与空间', '玻璃器皿的质感', '石膏五官结构', '头像比例与透视', '质感对比与边缘处理', '完整静物的深入塑造', '限时写生与评讲', '阶段作品整理'],
            'water': ['调色与色卡练习', '叶片的冷暖变化', '湿画法与边缘控制', '一枝秋叶的观察', '花瓶与水果小品', '水分控制与叠色', '窗边静物的光色', '小幅风景写生', '秋冬植物色彩记录'],
            'creative': ['线条里的小花园', '纸上的秋天', '形状变成小动物', '树叶拓印与组合', '我家的窗台', '冷暖色的故事', '拼贴画中的城市', '冬天的手套', '我的色彩日记'],
            'ink': ['中锋用笔与墨色', '竹叶的起收笔', '竹竿穿插与构图', '兰叶的疏密变化', '小写意花卉入门', '花鸟画的留白', '水墨浓淡练习', '竹石小品临摹', '落款与画面整理'],
            'oil': ['油画工具与调色', '单色静物起稿', '天空的冷暖层次', '远山与空间关系', '树木的色块概括', '小幅风景铺色', '笔触与细节取舍', '风景作品整体调整', '阶段作品分享'],
        }
        lesson_records = []
        missing = []
        monday = self.today-timedelta(days=self.today.weekday())
        for i, (cls, cat, weekday, hour, room, age) in enumerate(specs):
            for week in range(-5, 4):
                day = monday+timedelta(weeks=week, days=weekday)
                begins = datetime.combine(day, time(hour))
                ends = begins+timedelta(hours=2)
                if self.query("SELECT id FROM lessons WHERE status<>'CANCELLED' AND start_time<%s AND end_time>%s AND (teacher_id=%s OR room_id=%s OR class_id=%s)",
                              (ends, begins, cls['teacher_id'], room, cls['id'])):
                    continue  # Existing lesson wins; do not rewrite its attendance/status.
                historical = day < self.today
                lid = self.add('lessons', class_id=cls['id'], teacher_id=cls['teacher_id'], room_id=room,
                               title=topics[cat][week+5], start_time=begins, end_time=ends,
                               status='COMPLETED' if historical else 'SCHEDULED', created_by=self.admin, created_at=created)
                record = {'id': lid, 'class': cls, 'category': cat, 'day': day, 'start': begins, 'end': ends, 'participants': []}
                for j, sid in enumerate(members[cls['id']]):
                    if sid == transfer_student and ((cls['id']==source and day>=transfer_day) or (cls['id']==target and day<transfer_day)):
                        continue
                    pid = self.add('lesson_participants', lesson_id=lid, student_id=sid, kind='REGULAR', status='ACTIVE', created_at=created)
                    record['participants'].append((sid, pid))
                    n = (i*17+(week+5)*11+j*7)%37
                    status = 'LEAVE' if n in (3, 15) else ('ABSENT' if n==22 else 'PRESENT')
                    if historical:
                        self.add('attendance', participant_id=pid, status=status, recorded_by=self.admin,
                                 remark='' if status=='PRESENT' else ('事先请假，后续安排补课。' if status=='LEAVE' else '未到课，已联系监护人确认。'), updated_at=ends+timedelta(minutes=10))
                        if status!='PRESENT':
                            missing.append((record, sid, pid, status))
                        if status=='LEAVE':
                            self.add('leaves', participant_id=pid, student_id=sid, lesson_id=lid,
                                     reason=['学校运动会时间冲突，申请本周请假。', '家中有事，无法按时送到画室。', '感冒需休息，本次课程请假。'][j%3],
                                     status='APPROVED', review_comment='同意请假，请按后续通知参加同主题补课。', reviewed_by=self.admin,
                                     created_at=begins-timedelta(days=2), reviewed_at=begins-timedelta(days=1))
                    elif week == 0 and j==1 and i in (0, 1, 4):
                        self.add('leaves', participant_id=pid, student_id=sid, lesson_id=lid,
                                 reason='学校临时安排活动，申请调到下一次补课。', status='PENDING', created_at=self.stamp(-1, 19))
                lesson_records.append(record)

        # Backfill only newly added enrollment participants into untouched existing lessons.
        # Original participants/attendance are preserved; transfer effective dates are respected.
        for cls in (sketch, water):
            for lesson in self.query('SELECT * FROM lessons WHERE class_id=%s', (cls['id'],)):
                for sid in members[cls['id']]:
                    if sid not in self.ids['students']:
                        continue
                    day = lesson['start_time'].date()
                    if day<start or (sid==transfer_student and day>=transfer_day):
                        continue
                    if self.query('SELECT id FROM lesson_participants WHERE lesson_id=%s AND student_id=%s', (lesson['id'], sid)):
                        continue
                    pid = self.add('lesson_participants', lesson_id=lesson['id'], student_id=sid, kind='REGULAR', status='ACTIVE', created_at=created)
                    if lesson['status']=='COMPLETED':
                        self.add('attendance', participant_id=pid, status='PRESENT', remark='', recorded_by=self.admin, updated_at=lesson['end_time']+timedelta(minutes=10))

        # Separate supplementary sessions, same subject/teacher, with no regular enrollment.
        for index, (original, sid, pid, status) in enumerate(missing[:12]):
            self.add('makeup_suggestions', original_participant_id=pid, teacher_id=original['class']['teacher_id'], created_by=self.admin,
                     comment='补齐本次观察与构图练习，携带原作业，由任课教师逐项讲解。', created_at=original['end']+timedelta(days=1))
            day = (original['day']+timedelta(days=3)) if index<7 else self.today+timedelta(days=3+index-7)
            begins = datetime.combine(day, time(17))
            ends = begins+timedelta(hours=1)
            if self.query("SELECT id FROM lessons WHERE status<>'CANCELLED' AND start_time<%s AND end_time>%s AND (teacher_id=%s OR room_id=%s)",
                          (ends, begins, original['class']['teacher_id'], rooms[1])):
                continue
            cid = self.add('classes', name='临时补课 · '+original['class']['name'], course_id=original['class']['course_id'],
                           teacher_id=original['class']['teacher_id'], capacity=4, status='ACTIVE', created_at=created) if index==0 else None
            # Reuse one workshop per subject instead of growing one class per student.
            key = original['class']['course_id']
            if not hasattr(self, 'workshops'):
                self.workshops = {}
            if cid:
                self.workshops[key] = cid
            if key not in self.workshops:
                # A supplementary session can belong to the original class; no extra regular members at this ad-hoc time.
                # Use the original existing workshop only for the sketch course; other subjects get one workshop each.
                workshop = self.add('classes', name='补课辅导 · '+original['class']['name'], course_id=key,
                                    teacher_id=original['class']['teacher_id'], capacity=4, status='ACTIVE', created_at=created)
                self.workshops[key] = workshop
            lid = self.add('lessons', class_id=self.workshops[key], teacher_id=original['class']['teacher_id'], room_id=rooms[1],
                           title={'sketch':'素描', 'advanced':'结构素描', 'water':'水彩', 'creative':'创意绘画', 'ink':'国画', 'oil':'油画'}[original['category']]+' · 缺课内容补习', start_time=begins, end_time=ends,
                           status='COMPLETED' if day<self.today else 'SCHEDULED', created_by=self.admin, created_at=original['end']+timedelta(days=1))
            target_pid = self.add('lesson_participants', lesson_id=lid, student_id=sid, kind='MAKEUP', status='ACTIVE',
                                  original_participant_id=pid, created_at=original['end']+timedelta(days=1))
            done = day<self.today
            self.add('makeups', original_participant_id=pid, target_lesson_id=lid, target_participant_id=target_pid,
                     student_id=sid, status='COMPLETED' if done else 'ARRANGED', reason='补齐'+original['start'].strftime('%m月%d日')+'缺课内容。',
                     created_by=self.admin, created_at=original['end']+timedelta(days=1), completed_at=ends+timedelta(minutes=5) if done else None)
            if done:
                self.add('attendance', participant_id=target_pid, status='PRESENT', recorded_by=self.admin,
                         remark='已补齐练习，课后按点评调整。', updated_at=ends+timedelta(minutes=5))

        # Two historical tasks and one upcoming task in each regular class.
        reviews = {
            'sketch': ('构图比较稳定，陶罐口沿的椭圆还可以压扁一些。下次先找准明暗交界线，再逐层加深暗部。', '暗部层次清楚了，注意桌面投影不要画成同样的黑度。'),
            'advanced': ('结构关系交代清楚，玻璃边缘略显生硬。请用相邻背景的灰度衬托透明感，减少轮廓线。', '边缘虚实有改善，前后物体的对比还可以再拉开一点。'),
            'water': ('叶片的冷暖变化观察得不错，叠色时底层还没干，局部出现了水痕。下一版减少来回涂抹。', '水分控制更稳定了，保留纸白的位置让画面更透气。'),
            'creative': ('花朵的大小有变化，颜色选择也有自己的想法。可以再加一些前后遮挡，让花园更有空间。', '增加高低变化后画面更丰富，背景留白可以继续保持。'),
            'ink': ('竹叶有浓淡变化，右侧三组叶片方向较接近。调整出笔方向，注意一笔完成，少回描。', '叶组疏密更自然了，竹节处的停顿再轻一些。'),
            'oil': ('远山和近景已经拉开，前景绿色稍显单一。加入少量暖色，让受光部分与背光部分有区别。', '冷暖关系更明确，最后整理时注意保留大笔触。'),
        }
        seed = 0
        for i, (cls, cat, wd, hour, room, age) in enumerate(specs):
            history = [r for r in lesson_records if r['class']['id']==cls['id'] and r['day']<self.today-timedelta(days=5)]
            future = [r for r in lesson_records if r['class']['id']==cls['id'] and r['day']>=self.today]
            for k, lesson in enumerate(history[-2:]+future[:1]):
                is_past = lesson['day']<self.today
                task_time = lesson['end']
                title = {'sketch':'几何体与陶罐组合', 'advanced':'静物结构与质感研究', 'water':'秋叶色彩观察', 'creative':'线条里的小花园', 'ink':'墨竹疏密练习', 'oil':'远山与近景色彩习作'}[cat]
                task = self.add('tasks', class_id=cls['id'], lesson_id=lesson['id'], title=title+(' · 修改练习' if k==1 else ''),
                                description='按课堂步骤完成一张习作，先保留起稿，再整理画面。提交时说明遇到的问题；根据点评可上传修改版本。',
                                due_date=lesson['day']+timedelta(days=5), created_by=self.admin,
                                created_at=task_time if is_past else self.stamp(-1, 12))
                if not is_past:
                    continue
                for j, (sid, pid) in enumerate(lesson['participants'][:4]):
                    seed += 1
                    submitted = task_time+timedelta(days=1, minutes=17*j)
                    wid = self.add('works', task_id=task, student_id=sid, title=title, created_at=submitted)
                    fid = self.file(f'practice-{seed:03d}', cat, seed, 1, submitted)
                    vid = self.add('work_versions', work_id=wid, file_id=fid, version_no=1,
                                   description=['先完成大形和主要色块，细节还在练习。', '尝试按照课堂的方法调整空间关系。', '这次重点练习了构图，局部不太确定。', '保留了起稿步骤，请老师看看比例。'][j], created_at=submitted)
                    if not (k==1 and j==3):
                        self.add('reviews', version_id=vid, teacher_id=cls['teacher_id'], content=reviews[cat][0],
                                 score=76+(i*3+j*4+k)%17, created_by=self.admin,
                                 created_at=datetime.combine(submitted.date()+timedelta(days=1), time(10, 15+j*7)))
                    if k==0 and j==0:
                        updated = submitted+timedelta(days=2)
                        fid = self.file(f'practice-{seed:03d}', cat, seed, 2, updated)
                        vid = self.add('work_versions', work_id=wid, file_id=fid, version_no=2,
                                       description='根据点评调整了局部关系，保留原构图，对重点部分做了修改。', created_at=updated)
                        self.add('reviews', version_id=vid, teacher_id=cls['teacher_id'], content=reviews[cat][1],
                                 score=87+i%6, created_by=self.admin,
                                 created_at=datetime.combine(updated.date()+timedelta(days=1), time(14, 30)))
            fid = self.file(f'reference-{i+1:02d}', cat, 100+i, 2, self.stamp(-12), purpose='resources')
            self.add('resources', title={'sketch':'静物观察与明暗参考', 'advanced':'结构与空间练习参考', 'water':'植物色彩观察参考', 'creative':'花园构图练习参考', 'ink':'竹叶与疏密练习参考', 'oil':'风景冷暖层次参考'}[cat],
                     class_id=cls['id'], file_id=fid, description='课堂练习示意图，仅供观察步骤与构图参考，不要求照抄。（虚构演示资料）',
                     created_by=self.admin, created_at=self.stamp(-12))

        for title, content, offset, cid in [
            ('本周上课与材料准备', '请按课表到课。素描班携带2B、4B铅笔和橡皮；水彩班带好水彩纸与吸水纸。画室备有画板和水桶。', -1, None),
            ('阶段作品整理提醒', '请将近四周的习作按完成时间整理，保留初稿和修改稿。教师将在下次课进行阶段讲评，不额外收费。', -3, None),
            ('请假与补课办理说明', '不能到课时请尽量提前一天提交申请。获批后由教务确认同主题补课时间，收到安排后再到课。', -7, None),
            ('素描班本周练习重点', '本周先检查构图和比例，再逐步整理黑白灰。请带上次的习作，用于课堂对照。', -2, sketch['id']),
            ('水彩纸张准备', '下次练习需要300g水彩纸两张，建议先裁成四开或八开。已有同规格纸张的学员无需重复购买。', -2, water['id']),
            ('画室开放练习时间', '周五16:00—18:00开放安静练习，需提前向教务登记。未成年人由监护人安排接送，不替代正式课程。', -9, None),
        ]:
            self.add('notices', title=title, content=content, class_id=cid, created_by=self.admin, created_at=self.stamp(offset))
        students_users = self.query("SELECT id FROM users WHERE username IN ('student','student2') AND read_only=FALSE ORDER BY id")
        feedbacks = [('希望提前一天收到材料准备提醒，临时购买不太方便。', '已在班级通知中提前说明，后续会在每周四汇总本周材料。'),
                     ('上次课程的明暗练习还有疑问，补课时能再讲一下吗？', '可以，补课会安排十分钟对照原作业讲解，请带上习作。'),
                     ('周六接送时间可能晚十分钟，想确认下课后的等候位置。', None),
                     ('作品上传后在哪里看老师的修改建议？', '进入作品列表，打开版本记录即可查看对应版本的点评。')]
        for i, (content, reply) in enumerate(feedbacks):
            if students_users:
                self.add('feedback', user_id=students_users[i%len(students_users)]['id'], content=content, reply=reply,
                         status='REPLIED' if reply else 'OPEN', created_at=self.stamp(-6+i, 18), replied_at=self.stamp(-5+i, 10) if reply else None)

        validation = self.validate()
        report = {'batch': BATCH, 'date': str(self.today), 'mode': 'applied' if self.apply else 'preview_rolled_back',
                  'added': {t: len(self.ids[t]) for t in TABLES}, 'renamed': self.renames, 'validation': validation,
                  'ids': dict(self.ids), 'uploaded_files': self.file_manifest}
        self.add('audit_logs', user_id=self.admin, action='DEMO_DATA_IMPORT', target_type='demo_fixture', target_id=BATCH,
                 detail=json.dumps(report, ensure_ascii=False), created_at=datetime.now())
        (self.output/('applied.json' if self.apply else 'preview.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        if self.apply:
            self.conn.commit()
        else:
            self.conn.rollback()
        return report

    def validate(self):
        # Validate only this batch so unrelated pre-existing issues are never silently rewritten.
        ids = self.ids['lessons']
        marks = ','.join(['%s']*len(ids))
        checks = {
            'teacher_room_class_conflicts': (f"SELECT COUNT(*) n FROM lessons a JOIN lessons b ON a.id<b.id AND a.start_time<b.end_time AND a.end_time>b.start_time AND (a.teacher_id=b.teacher_id OR a.room_id=b.room_id OR a.class_id=b.class_id) WHERE a.status<>'CANCELLED' AND b.status<>'CANCELLED' AND (a.id IN ({marks}) OR b.id IN ({marks}))", ids+ids),
            'student_time_conflicts': (f"SELECT COUNT(*) n FROM lesson_participants a JOIN lesson_participants b ON a.student_id=b.student_id AND a.lesson_id<b.lesson_id JOIN lessons la ON la.id=a.lesson_id JOIN lessons lb ON lb.id=b.lesson_id WHERE a.status='ACTIVE' AND b.status='ACTIVE' AND la.status<>'CANCELLED' AND lb.status<>'CANCELLED' AND la.start_time<lb.end_time AND la.end_time>lb.start_time AND (la.id IN ({marks}) OR lb.id IN ({marks}))", ids+ids),
            'over_capacity': (f"SELECT COUNT(*) n FROM (SELECT l.id,LEAST(c.capacity,r.capacity) cap FROM lessons l JOIN classes c ON c.id=l.class_id JOIN rooms r ON r.id=l.room_id JOIN lesson_participants p ON p.lesson_id=l.id AND p.status='ACTIVE' WHERE l.id IN ({marks}) GROUP BY l.id,c.capacity,r.capacity HAVING COUNT(*)>cap) t", ids),
            'invalid_regular_enrollment': (f"SELECT COUNT(*) n FROM lesson_participants p JOIN lessons l ON l.id=p.lesson_id WHERE l.id IN ({marks}) AND p.kind='REGULAR' AND NOT EXISTS(SELECT 1 FROM enrollments e WHERE e.student_id=p.student_id AND e.class_id=l.class_id AND e.effective_date<=DATE(l.start_time) AND (e.end_date IS NULL OR e.end_date>DATE(l.start_time)))", ids),
            'future_attendance': (f"SELECT COUNT(*) n FROM attendance a JOIN lesson_participants p ON p.id=a.participant_id JOIN lessons l ON l.id=p.lesson_id WHERE l.id IN ({marks}) AND l.start_time>%s AND a.status IN ('PRESENT','ABSENT')", ids+[datetime.combine(self.today,time())]),
            'missing_completed_attendance': (f"SELECT COUNT(*) n FROM lesson_participants p JOIN lessons l ON l.id=p.lesson_id LEFT JOIN attendance a ON a.participant_id=p.id WHERE l.id IN ({marks}) AND l.status='COMPLETED' AND a.id IS NULL", ids),
        }
        result = {name:self.query(sql,args)[0]['n'] for name,(sql,args) in checks.items()}
        if self.ids['makeups']:
            mid = self.ids['makeups']
            result['invalid_makeup_chain'] = self.query(f"SELECT COUNT(*) n FROM makeups m JOIN lesson_participants op ON op.id=m.original_participant_id JOIN lessons ol ON ol.id=op.lesson_id JOIN lesson_participants tp ON tp.id=m.target_participant_id JOIN lessons tl ON tl.id=tp.lesson_id JOIN attendance oa ON oa.participant_id=op.id LEFT JOIN attendance ta ON ta.participant_id=tp.id WHERE m.id IN ({','.join(['%s']*len(mid))}) AND (op.student_id<>tp.student_id OR m.student_id<>tp.student_id OR tp.original_participant_id<>op.id OR m.target_lesson_id<>tl.id OR tp.kind<>'MAKEUP' OR tl.start_time<ol.end_time OR oa.status NOT IN ('LEAVE','ABSENT') OR (m.status='COMPLETED' AND (ta.status IS NULL OR ta.status<>'PRESENT' OR m.completed_at<tl.end_time)))",mid)[0]['n']
        wid = self.ids['works']
        if wid:
            placeholders = ','.join(['%s']*len(wid))
            result['invalid_work_enrollment_or_dates'] = self.query(f"SELECT COUNT(*) n FROM works w JOIN tasks t ON t.id=w.task_id JOIN work_versions v ON v.work_id=w.id JOIN files f ON f.id=v.file_id LEFT JOIN reviews r ON r.version_id=v.id WHERE w.id IN ({placeholders}) AND (w.created_at<t.created_at OR v.created_at<w.created_at OR f.created_at>v.created_at OR DATE(v.created_at)>t.due_date OR r.created_at<v.created_at OR NOT EXISTS(SELECT 1 FROM enrollments e WHERE e.student_id=w.student_id AND e.class_id=t.class_id AND e.effective_date<=DATE(w.created_at) AND (e.end_date IS NULL OR e.end_date>DATE(w.created_at))))",wid)[0]['n']
        if any(result.values()):
            raise ValueError('Fixture validation failed: '+json.dumps(result))
        return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apply', action='store_true', help='Commit fictional data; default is rollback preview')
    p.add_argument('--date', type=date.fromisoformat, default=datetime.now(timezone(timedelta(hours=8))).date())
    p.add_argument('--api', default=os.environ.get('DEMO_API_URL','http://127.0.0.1:18080/api'))
    p.add_argument('--output', type=Path, default=Path('work/demo-data'))
    args = p.parse_args()
    import pymysql
    conn = pymysql.connect(host=os.environ.get('DEMO_DB_HOST','127.0.0.1'), port=int(os.environ.get('DEMO_DB_PORT','3306')),
                           user=os.environ['DEMO_DB_USER'], password=os.environ['DEMO_DB_PASSWORD'],
                           database=os.environ.get('DEMO_DB_NAME','studio'), charset='utf8mb4',
                           cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    try:
        conn.query('SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED')
        result = Seeder(conn, args.date, args.apply, args.api, args.output).run()
        print(json.dumps({k:v for k,v in result.items() if k not in ('ids','uploaded_files')},ensure_ascii=False,indent=2))
    finally:
        conn.close()


if __name__=='__main__':
    main()
