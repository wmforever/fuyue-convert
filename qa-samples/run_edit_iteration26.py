#!/usr/bin/env python3
"""Bounded current-build integration smoke using a frozen dependency graph and public subreaper."""
import argparse,copy,hashlib,io,json,os,pathlib,resource,secrets,socket,subprocess,threading,time,xml.etree.ElementTree as ET,zipfile
from qa_process_guard import ManagedProcess,install_shutdown_handlers,snapshot
from qa_http_deadline import Deadline,request as bounded_request
ROOT=pathlib.Path(__file__).resolve().parents[1]
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--jar',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--resume-from',type=pathlib.Path);p.add_argument('--keep-going',action='store_true');p.add_argument('--corpus',type=pathlib.Path,default=ROOT/'qa-samples/generated/edit-iteration26');a=p.parse_args()
    install_shutdown_handlers();out=a.out.resolve();assert not out.exists();out.mkdir(parents=True)
    corpus=a.corpus.resolve();truth=json.loads((corpus/'expected.json').read_text())
    baseline=snapshot();started=time.monotonic();suite=Deadline(480);cpu=resource.getrusage(resource.RUSAGE_CHILDREN)
    report={'jarSha256':sha(a.jar),'manifestSha256':sha(corpus/'expected.json'),'cases':[],'status':'running','failures':[],'workerPids':[],'helperSha256':{n:sha(ROOT/'qa-samples'/n) for n in ['run_edit_iteration26.py','qa_http_deadline.py','qa_process_guard.py']}}
    def save():(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    token=secrets.token_urlsafe(40)
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    env={**os.environ,'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':str(port),'FORMAT_CONVERTER_API_TOKEN':token,
         'FORMAT_CONVERTER_DATA_ROOT':str(out/'private-data'),'FORMAT_CONVERTER_OCR_ENABLED':'true'}
    env.pop('FORMAT_CONVERTER_TESSERACT_BINARY',None)
    assert pathlib.Path(env['FORMAT_CONVERTER_APP_HOME'],'ocr','OCR-RUNTIME.json').is_file()
    def request(path,deadline,data=None,headers=None):
        return bounded_request(port,token,path,deadline,data,headers)
    workers={};stop=threading.Event()
    def observe():
        while not stop.wait(.04):
            rows=snapshot()
            # ForkedFileConverter starts each Worker directly from the backend.
            # A Worker's child can briefly expose an inherited matching command;
            # that descendant is not another backend Worker.
            for pid in [pid for pid,r in rows.items() if r['parent']==backend_pid]:
                try:
                    if b'ConversionWorkerMain' in (pathlib.Path('/proc')/str(pid)/'cmdline').read_bytes():workers[(pid,rows[pid]['startTicks'])]=rows[pid]
                except OSError:pass
    def convert(name,data,target,case,stage):
        contract=Deadline(120,suite);contract.remaining()
        boundary='qa-'+secrets.token_hex(16)
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="targetFormat"\r\n\r\n{target}\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
        begin=time.monotonic();task=json.loads(request('/api/tasks',contract,body,{'Content-Type':'multipart/form-data; boundary='+boundary}));path='/api/tasks/'+task['taskId']
        while True:
            contract.remaining()
            task=json.loads(request(path,contract))
            if task['status'] in ['SUCCESS','FAILED','CANCELLED']:break
            contract.sleep(.1)
        rec={'case':case,'stage':stage,'target':target,'seconds':time.monotonic()-begin,'task':task};report['cases'].append(rec);save()
        if task['status']!='SUCCESS' or not task['downloadReady']:
            report['failures'].append({'type':'ConversionFailure','action':case,'errorCode':task.get('errorCode')});save()
            if a.keep_going:return None
            raise AssertionError(rec)
        artifact=out/(case+'-'+stage+'.'+target);artifact.write_bytes(request(path+'/download',contract))
        contract.remaining();rec.update(artifact=artifact.name,sha256=sha(artifact),seconds=time.monotonic()-begin);save();return artifact
    with (out/'private-server.log').open('x') as log:
        process=ManagedProcess(['java','-jar',str(a.jar.resolve())],receipt=out/'supervision.json',env=env,stdout=log,stderr=subprocess.STDOUT)
        backend_pid=json.loads((out/'supervision.json').read_text())['root']['pid']
        observer=threading.Thread(target=observe,daemon=True);observer.start()
        try:
            startup=Deadline(36,suite)
            while True:
                startup.remaining()
                try:report['health']=json.loads(request('/api/health',startup));break
                except Exception:
                    if process.poll() is not None:raise RuntimeError('server exited')
                    startup.sleep(.2)
            assert report['health']['ocr']['available'] and report['health']['ocr']['bundled']
            assert report['health']['office']['available']
            report['capabilities']=json.loads(request('/api/tasks/capabilities',suite));save()
            artifacts={};start=0
            if a.resume_from:
                previous=json.loads((a.resume_from/'report.json').read_text())
                assert previous['jarSha256']==report['jarSha256'] and previous['manifestSha256']==report['manifestSha256']
                start=len(previous['cases']);assert [c['case'] for c in previous['cases']]==[v['id'] for v in truth['actions'][:start]]
                for c in previous['cases']:
                    if 'artifact' in c:
                        path=(a.resume_from/c['artifact']).resolve();assert sha(path)==c['sha256'];artifacts[c['case']]=path
                report['resumedFrom']={'reportSha256':sha(a.resume_from/'report.json'),'attemptedActions':start,'source':str(a.resume_from)};save()
            for action in truth['actions'][start:]:
                name=action['id']
                if action['input'].startswith('@') and not artifacts.get(action['input'][1:]):
                    report.setdefault('unsupportedEdits',[]).append({'action':name,'reason':'input not produced'});save();continue
                source=artifacts[action['input'][1:]] if action['input'].startswith('@') else corpus/action['input']
                if not action['input'].startswith('@'):assert sha(source)==truth['sources'][action['input']]
                data=source.read_bytes();filename=source.name
                if action.get('edit'):
                    with zipfile.ZipFile(io.BytesIO(data)) as z:parts={n:z.read(n) for n in z.namelist()}
                    root=ET.fromstring(parts['word/document.xml']);old=action['edit']['old'];new=action['edit']['new'];nodes=[n for n in root.iter(W+'t') if old in (n.text or '')]
                    if len(nodes)!=1 or nodes[0].text.count(old)!=1:
                        report.setdefault('unsupportedEdits',[]).append({'action':name,'reason':'original amount not recognized in exactly one editable run','old':old});save();continue
                    nodes[0].text=nodes[0].text.replace(old,new);parts['word/document.xml']=ET.tostring(root,encoding='utf-8',xml_declaration=True);buf=io.BytesIO()
                    with zipfile.ZipFile(buf,'w',zipfile.ZIP_DEFLATED) as z:
                        for n,v in parts.items():z.writestr(n,v)
                    data=buf.getvalue();filename=name+'-edited.docx';edited=out/filename;edited.write_bytes(data);report.setdefault('edits',[]).append({'action':name,'artifact':filename,'sha256':sha(edited),'change':action['edit']})
                artifacts[name]=convert(filename,data,action['target'],name,'result')
                print(name,'completed',flush=True)
            suite.remaining();report['status']='completed-with-failures' if report['failures'] else 'completed'
        except BaseException as error:
            report['status']='failed';report['failures'].append({'type':type(error).__name__,'message':str(error)});raise
        finally:
            try:report['supervision']=process.shutdown()
            finally:stop.set();observer.join(timeout=2)
            report['workerPids']=sorted(r['pid'] for r in workers.values());report['workerIdentities']=list(workers.values());now=snapshot()
            report['newZombies']=[r for pid,r in now.items() if r['state']=='Z' and (pid not in baseline or baseline[pid]['startTicks']!=r['startTicks'])]
            finish=resource.getrusage(resource.RUSAGE_CHILDREN)
            report['resources']={'wallSeconds':time.monotonic()-started,'userSeconds':finish.ru_utime-cpu.ru_utime,'systemSeconds':finish.ru_stime-cpu.ru_stime,'maxChildRssKiB':finish.ru_maxrss}
            if report['newZombies'] or (report['status'].startswith('completed') and len(workers)!=len(report['cases'])):
                report['status']='failed'
                report['failures'].append({'type':'FinalObservationFailure','workers':len(workers),'contracts':len(report['cases']),'newZombies':report['newZombies']})
            save();assert not report['newZombies']
            if report['status'].startswith('completed'):assert len(workers)==len(report['cases'])
if __name__=='__main__':main()
