"""Upgrade only the explicit realistic-teaching-v1 demo batch.

Default: generate the SVG/PNG assets and a reviewable plan without changing data.
--apply uploads private files through the existing application API, then commits
version/feedback changes in one guarded transaction. Prior files are preserved.
"""
import argparse
from collections import defaultdict
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.request
import uuid

from artwork_svg import draw_exercise

BATCH='realistic-teaching-v1'
UPGRADE='teaching-artwork-svg-v2'
CATEGORIES={'几何体与陶罐组合':'sketch','静物结构与质感研究':'advanced','秋叶色彩观察':'water','线条里的小花园':'creative','墨竹疏密练习':'ink','远山与近景色彩习作':'oil'}
NOTES={
 'sketch':('先确定陶罐、方体和梨的位置，保留辅助线，暗部只铺了一层。','依据点评加深陶罐背光面与方体侧面，补上梨的投影，保留亮部。','整理陶罐反光和口沿，压低接触处的暗部，清理多余辅助线。'),
 'advanced':('先画组合静物的结构和主要灰面，玻璃边缘仍用了较重的线。','弱化玻璃轮廓，用衬布和相邻灰面衬托透明感，拉开前后关系。','补充玻璃口沿与底部反光，统一陶罐、方体及玻璃的明暗层次。'),
 'water':('用薄色铺出枝条和叶片的大体冷暖，叶脉尚未深入，局部有水痕。','待底色干后叠加少量叶脉和斑驳色，减轻水痕，保留纸白。','局部提亮主叶脉，整理叶缘，保留松动笔触，避免整体重复罩色。'),
 'creative':('完成花朵与蝴蝶的位置和色块，保留了构图时的铅笔圆线。','清理辅助线，增加花瓣纹理与近处草叶，表现花园的前后层次。','统一花瓣线条和蝴蝶的细节，保留花朵的高低与疏密变化。'),
 'ink':('先练竹竿、竹节和叶组，右侧叶片方向偏重复，浓淡变化较少。','调整叶组的出笔方向，增加浓淡层次，在竹石之间补上细草。','进一步整理竹节和叶片细线，让枝叶的虚实与留白更自然。'),
 'oil':('先铺天空、远山和近景的大色块，前景以单一绿色为主。','增加近景黄绿与蓝绿笔触，加深树丛暗部，拉开远山与前景的冷暖。','补充前景细草和受光笔触，整理山体边缘，保持远景柔和、近景清楚。'),
}
REVIEWS={
 'sketch':('构图稳定，陶罐体积已出现。暗部与方体侧面仍偏浅，梨的投影也需要交代。下一版注意亮部留白。','暗部和投影比初稿清楚，空间关系更完整。陶罐口沿与反光可再整理，避免把暗面全涂成同一黑度。','口沿、接触暗部和反光衔接较自然，保留了亮部，画面的黑白灰关系完整。'),
 'advanced':('主要结构关系明确，玻璃仍像用线围起来的实心物。尝试减轻轮廓，用相邻灰面表现透明质感。','玻璃轮廓减弱后通透了一些，前后层次也更清楚。口沿和杯底反光还可以更简练。','玻璃口沿、杯底与相邻物体的虚实处理较完整，注意后续练习继续保持整体观察。'),
 'water':('叶片冷暖分组清楚，但叶脉较弱，部分水痕偏硬。等底色干透后少量叠色，避免来回涂抹。','叠色和叶脉更有节制，纸白保留较好。可以挑选几片主叶细化，其余保持概括。','主叶与次叶的层次自然，叶缘和叶脉的轻重有变化，保留了水彩的透气感。'),
 'creative':('花朵高低安排有变化，配色协调。辅助圆线和地面还没整理，可增加近处草叶来表现前后关系。','辅助线清理后画面更干净，花瓣细节和草叶让层次丰富了，主体仍保持明确。','花瓣、蝴蝶与草叶细节统一，花园疏密自然，是一次完整的构图练习。'),
 'ink':('竹叶已经有起收笔，几组叶片方向过于一致。尝试改变叶组角度，用浓淡区分前后。','叶组方向与浓淡有明显改进，竹石之间的小草连接自然。竹节和细线仍可更轻。','竹竿、叶组和石块的疏密较协调，留白完整，线条不必再反复描补。'),
 'oil':('远近大色块区分明确，但前景绿色偏单一，树丛体积不足。试着加入暖黄绿受光色和冷绿暗部。','前景冷暖与树丛暗部拉开了，远山保持克制。最后整理可补少量细草和受光笔触。','近景细节有选择地补充，远山与天空仍保持整体，注意后续不要让每处笔触都一样清楚。'),
}


class Upgrade:
    def __init__(self,conn,output,api='',apply=False,assets=None):
        self.conn=conn;self.output=Path(output);self.api=api.rstrip('/');self.apply=apply
        self.assets=Path(assets) if assets else self.output/'assets'
        self.output.mkdir(parents=True,exist_ok=True);self.assets.mkdir(parents=True,exist_ok=True)
        self.token=None;self.files={};self.manifest=self.output/'uploaded-files.json'
        if self.manifest.exists():self.files=json.loads(self.manifest.read_text(encoding='utf-8'))

    def query(self,sql,args=()):
        with self.conn.cursor() as c:c.execute(sql,args);return c.fetchall()

    def execute(self,sql,args=()):
        with self.conn.cursor() as c:c.execute(sql,args);return c.lastrowid

    def snapshot(self):
        rows=self.query("SELECT detail FROM audit_logs WHERE action='DEMO_DATA_IMPORT' AND target_id=%s ORDER BY id DESC LIMIT 1",(BATCH,))
        if not rows:raise ValueError('Expected explicit demo import marker; refusing to change unrelated data')
        original=json.loads(rows[0]['detail']);ids=original['ids']['works'];marks=','.join(['%s']*len(ids))
        works=self.query(f'''SELECT w.*,t.class_id,t.due_date,c.teacher_id FROM works w JOIN tasks t ON t.id=w.task_id JOIN classes c ON c.id=t.class_id WHERE w.id IN ({marks}) ORDER BY w.id''',ids)
        versions=self.query(f'''SELECT v.*,f.original_name FROM work_versions v JOIN files f ON f.id=v.file_id WHERE v.work_id IN ({marks}) ORDER BY v.work_id,v.version_no''',ids)
        reviews=self.query(f'SELECT r.* FROM reviews r JOIN work_versions v ON v.id=r.version_id WHERE v.work_id IN ({marks}) ORDER BY r.id',ids)
        resources=self.query(f"SELECT * FROM resources WHERE id IN ({','.join(['%s']*len(original['ids']['resources']))}) ORDER BY id",original['ids']['resources'])
        admin=self.query("SELECT id FROM users WHERE username='admin' AND role='ADMIN'")[0]['id']
        return dict(works=works,versions=versions,reviews=reviews,resources=resources,admin=admin)

    def image(self,name,category,seed,revision):
        path=self.assets/(name+'.png')
        if not path.exists():draw_exercise(path,category,seed,revision)
        return path

    def upload(self,path,when,purpose):
        digest=hashlib.sha256(path.read_bytes()).hexdigest();key=path.name
        if key in self.files:
            previous=self.files[key]
            existing=self.query('SELECT id,original_name FROM files WHERE id=%s',(previous['id'],))
            if existing and existing[0]['original_name']==key and previous['sha256']==digest:return previous['id']
            raise ValueError('Upload journal differs from database or asset; use a new output directory')
        if not self.token:
            payload=json.dumps({'username':os.environ['DEMO_API_USERNAME'],'password':os.environ['DEMO_API_PASSWORD']}).encode()
            with urllib.request.urlopen(urllib.request.Request(self.api+'/auth/login',payload,{'Content-Type':'application/json'}),timeout=30) as r:res=json.load(r)
            if res.get('code')!=0:raise RuntimeError('Application login failed')
            self.token=res['data']['token']
        boundary=uuid.uuid4().hex
        payload=(f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n{purpose}\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\nContent-Type: image/png\r\n\r\n').encode()+path.read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
        request=urllib.request.Request(self.api+'/files',payload,{'Authorization':'Bearer '+self.token,'Content-Type':'multipart/form-data; boundary='+boundary})
        with urllib.request.urlopen(request,timeout=60) as r:res=json.load(r)
        if res.get('code')!=0:raise RuntimeError('Private file upload failed')
        ident=res['data']['id'];self.files[key]={'id':ident,'sha256':digest,'name':key,'when':str(when),'purpose':purpose}
        self.manifest.write_text(json.dumps(self.files,ensure_ascii=False,indent=2),encoding='utf-8')
        if len(self.files)%10==0:print(f'Uploaded {len(self.files)} images',flush=True)
        return ident

    def run(self):
        if self.query("SELECT id FROM audit_logs WHERE action='DEMO_ARTWORK_UPGRADE' AND target_id=%s",(UPGRADE,)):
            self.conn.rollback();return {'status':'already_upgraded','batch':UPGRADE}
        before=self.snapshot();self.conn.rollback()
        (self.output/'before.json').write_text(json.dumps(before,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
        bywork=defaultdict(list);byversion=defaultdict(list)
        for v in before['versions']:bywork[v['work_id']].append(v)
        for review in before['reviews']:byversion[review['version_id']].append(review)
        plan=[];resource_plan=[]
        for work in before['works']:
            old=bywork[work['id']];first=old[0];cat=CATEGORIES[work['title']]
            if len(old)>2:raise ValueError('A demo work has extra versions; review it manually before applying')
            match=re.fullmatch(r'practice-(\d+)-v1.png',first['original_name'])
            if not match:raise ValueError('Demo file was manually replaced; refusing to overwrite')
            seed=int(match.group(1));reviewed=bool(byversion[first['id']]);count=(3 if len(old)==2 else 2) if reviewed else 1
            start=first['created_at']
            for revision in range(1,count+1):
                previous=next((v for v in old if v['version_no']==revision),None)
                when=previous['created_at'] if previous else (start+timedelta(days=revision if revision==2 else 3)).replace(hour=18,minute=0,second=0)
                if when.date()>work['due_date']:raise ValueError('Planned revision would exceed assignment deadline')
                image=self.image(f'study-{cat}-{seed:03d}-v{revision}',cat,seed,revision)
                score=min(95,74+seed%9+(revision-1)*6)
                plan.append(dict(work_id=work['id'],category=cat,revision=revision,existing=next((v['id'] for v in old if v['version_no']==revision),None),when=when,path=image,description=NOTES[cat][revision-1],teacher=work['teacher_id'],review=REVIEWS[cat][revision-1] if reviewed else None,score=score))
        for i,resource in enumerate(before['resources']):
            cat=next(cat for text,cat in [('明暗','sketch'),('结构','advanced'),('植物','water'),('花园','creative'),('竹叶','ink'),('风景','oil')] if text in resource['title'])
            path=self.image(f'reference-{cat}-{i+101:03d}-v3',cat,101+i,3)
            resource_plan.append(dict(id=resource['id'],path=path,when=resource['created_at']))
        summary={'batch':UPGRADE,'works':len(before['works']),'versions':len(plan),'new_versions':sum(not x['existing'] for x in plan),'reviews':sum(bool(x['review']) for x in plan),'resources':len(resource_plan),'images':len(plan)+len(resource_plan)}
        (self.output/'plan.json').write_text(json.dumps({'summary':summary,'versions':plan,'resources':resource_plan},ensure_ascii=False,indent=2,default=str),encoding='utf-8')
        print(json.dumps(summary,ensure_ascii=False),flush=True)
        if not self.apply:return {**summary,'status':'prepared_no_database_changes'}
        for item in plan+resource_plan:item['file_id']=self.upload(item['path'],item['when'],'works' if 'work_id' in item else 'resources')
        self.conn.rollback();self.conn.begin()
        try:
            self.query('SELECT id FROM schedule_guard WHERE id=1 FOR UPDATE')
            current=self.snapshot()
            if current!=before:raise ValueError('Demo data changed since preparation; aborting guarded update')
            changes={'versions':[],'reviews':[],'resources':[]}
            for item in plan:
                if item['existing']:
                    vid=item['existing'];self.execute('UPDATE work_versions SET file_id=%s,description=%s WHERE id=%s',(item['file_id'],item['description'],vid))
                else:
                    vid=self.execute('INSERT INTO work_versions(work_id,file_id,version_no,description,created_at) VALUES(%s,%s,%s,%s,%s)',(item['work_id'],item['file_id'],item['revision'],item['description'],item['when']))
                    changes['versions'].append(vid)
                # Keep historical version timestamps; stamp replacement file no later than submission.
                actual=next((v['created_at'] for v in before['versions'] if v['id']==vid),item['when'])
                self.execute('UPDATE files SET created_at=%s WHERE id=%s AND owner_id=%s',(actual,item['file_id'],before['admin']))
                if item['review']:
                    old_reviews=byversion.get(vid,[])
                    if old_reviews:
                        self.execute('UPDATE reviews SET content=%s,score=%s WHERE id=%s',(item['review'],item['score'],old_reviews[0]['id']))
                    else:
                        reviewed_at=(actual+timedelta(days=1)).replace(hour=10,minute=30,second=0)
                        rid=self.execute('INSERT INTO reviews(version_id,teacher_id,content,score,created_by,created_at) VALUES(%s,%s,%s,%s,%s,%s)',(vid,item['teacher'],item['review'],item['score'],before['admin'],reviewed_at));changes['reviews'].append(rid)
            for item in resource_plan:
                self.execute('UPDATE resources SET file_id=%s WHERE id=%s',(item['file_id'],item['id']))
                self.execute('UPDATE files SET created_at=%s WHERE id=%s AND owner_id=%s',(item['when'],item['file_id'],before['admin']))
                changes['resources'].append(item['id'])
            for work in before['works']:
                vs=self.query('SELECT version_no,created_at FROM work_versions WHERE work_id=%s ORDER BY version_no',(work['id'],))
                if [v['version_no'] for v in vs]!=list(range(1,len(vs)+1)):raise ValueError('Non-contiguous version history')
                if any(vs[i]['created_at']>vs[i+1]['created_at'] for i in range(len(vs)-1)):raise ValueError('Revision timestamps not chronological')
            self.execute('INSERT INTO audit_logs(user_id,action,target_type,target_id,detail,created_at) VALUES(%s,%s,%s,%s,%s,%s)',(before['admin'],'DEMO_ARTWORK_UPGRADE','demo_fixture',UPGRADE,json.dumps(summary,ensure_ascii=False),datetime.now()))
            self.conn.commit()
            (self.output/'applied.json').write_text(json.dumps({'summary':summary,'changes':changes},ensure_ascii=False,indent=2),encoding='utf-8')
            return {**summary,'status':'applied'}
        except Exception:self.conn.rollback();raise


if __name__=='__main__':
    import pymysql
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true');parser.add_argument('--output',type=Path,default=Path('work/artwork-upgrade'))
    parser.add_argument('--api',default=os.environ.get('DEMO_API_URL','http://127.0.0.1:18080/api'))
    args=parser.parse_args()
    conn=pymysql.connect(host=os.environ.get('DEMO_DB_HOST','127.0.0.1'),port=int(os.environ.get('DEMO_DB_PORT','3306')),user=os.environ['DEMO_DB_USER'],password=os.environ['DEMO_DB_PASSWORD'],database=os.environ.get('DEMO_DB_NAME','studio'),charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor)
    try:print(json.dumps(Upgrade(conn,args.output,args.api,args.apply).run(),ensure_ascii=False))
    finally:conn.close()
