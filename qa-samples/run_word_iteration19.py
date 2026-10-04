#!/usr/bin/env python3
"""Sequential authenticated PDF/Word/Office acceptance using the public subreaper."""
import argparse,copy,hashlib,io,json,os,pathlib,resource,secrets,socket,subprocess,threading,time,urllib.request,xml.etree.ElementTree as ET,zipfile
from qa_process_guard import ManagedProcess,install_shutdown_handlers,snapshot
ROOT=pathlib.Path(__file__).resolve().parents[1]
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def edited(data,kind):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        entries={n:z.read(n) for n in z.namelist()}
    root=ET.fromstring(entries['word/document.xml'])
    if kind=='continuous':
        paragraphs=root.find(W+'body').findall(W+'p');target=max(paragraphs,key=lambda p:sum(len(t.text or '') for t in p.iter(W+'t')))
        run=copy.deepcopy(target.findall(W+'r')[-1])
        for node in list(run):
            if node.tag!=W+'rPr':run.remove(node)
        text=ET.SubElement(run,W+'t');text.set('{http://www.w3.org/XML/1998/namespace}space','preserve')
        text.text=' '+('Additional approved delivery records must retain every identifier and remain editable after the review. '*35).strip();target.append(run)
        change={'append':text.text}
    else:
        found=[n for cell in root.iter(W+'tc') for n in cell.iter(W+'t') if n.text=='.95'];assert len(found)==1
        found[0].text='.96';change={'replace':['.95','.96'],'cellOnly':True}
    entries['word/document.xml']=ET.tostring(root,encoding='utf-8',xml_declaration=True)
    result=io.BytesIO()
    with zipfile.ZipFile(result,'w',zipfile.ZIP_DEFLATED) as z:
        for name,data in entries.items():z.writestr(name,data)
    return result.getvalue(),change
def main():
    p=argparse.ArgumentParser();p.add_argument('--jar',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--edits',action='store_true');a=p.parse_args()
    install_shutdown_handlers();out=a.out.resolve();assert not out.exists();out.mkdir(parents=True)
    corpus=ROOT/'qa-samples/generated/word-iteration19';truth=json.loads((corpus/'expected.json').read_text())
    baseline=snapshot();started=time.monotonic();cpu=resource.getrusage(resource.RUSAGE_CHILDREN)
    report={'jarSha256':sha(a.jar),'manifestSha256':sha(corpus/'expected.json'),'cases':[],'status':'running','failures':[],'workerPids':[]}
    def save():(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    token=secrets.token_urlsafe(40)
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    env={**os.environ,'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':str(port),'FORMAT_CONVERTER_API_TOKEN':token,
         'FORMAT_CONVERTER_DATA_ROOT':str(out/'private-data'),'FORMAT_CONVERTER_OCR_ENABLED':'false'}
    def request(path,data=None,headers=None):
        with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=data,headers={'X-Format-Converter-Token':token,**(headers or {})}),timeout=30) as r:return r.read()
    workers=set();stop=threading.Event()
    def observe():
        while not stop.wait(.04):
            rows=snapshot()
            # ForkedFileConverter starts each Worker directly from the backend.
            # A Worker's child can briefly expose an inherited matching command;
            # that descendant is not another backend Worker.
            for pid in [pid for pid,r in rows.items() if r['parent']==backend_pid]:
                try:
                    if b'ConversionWorkerMain' in (pathlib.Path('/proc')/str(pid)/'cmdline').read_bytes():workers.add(pid)
                except OSError:pass
    def convert(name,data,target,case,stage):
        if time.monotonic()-started>480:raise TimeoutError('suite480s')
        boundary='qa-'+secrets.token_hex(16)
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="targetFormat"\r\n\r\n{target}\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
        begin=time.monotonic();task=json.loads(request('/api/tasks',body,{'Content-Type':'multipart/form-data; boundary='+boundary}));path='/api/tasks/'+task['taskId']
        while time.monotonic()-begin<120:
            task=json.loads(request(path))
            if task['status'] in ['SUCCESS','FAILED','CANCELLED']:break
            time.sleep(.1)
        rec={'case':case,'stage':stage,'target':target,'seconds':time.monotonic()-begin,'task':task};report['cases'].append(rec);save()
        assert task['status']=='SUCCESS' and task['downloadReady'],rec
        artifact=out/(case+'-'+stage+'.'+target);artifact.write_bytes(request(path+'/download'))
        rec.update(artifact=artifact.name,sha256=sha(artifact));save();return artifact
    with (out/'private-server.log').open('x') as log:
        process=ManagedProcess(['java','-jar',str(a.jar.resolve())],receipt=out/'supervision.json',env=env,stdout=log,stderr=subprocess.STDOUT)
        backend_pid=json.loads((out/'supervision.json').read_text())['root']['pid']
        observer=threading.Thread(target=observe,daemon=True);observer.start()
        try:
            for _ in range(180):
                try:report['health']=json.loads(request('/api/health'));break
                except Exception:
                    if process.poll() is not None:raise RuntimeError('server exited')
                    time.sleep(.2)
            else:raise TimeoutError('startup36s')
            for case in truth['cases']:
                source=corpus/case['file'];assert sha(source)==case['sha256'];name=source.stem
                word=convert(source.name,source.read_bytes(),'docx',name,'word')
                pdf=convert(word.name,word.read_bytes(),'pdf',name,'office')
                convert(pdf.name,pdf.read_bytes(),'txt',name,'text')
                if a.edits and name in ['continuous-en','numeric-table']:
                    data,change=edited(word.read_bytes(),'continuous' if name=='continuous-en' else 'table')
                    edit=out/(name+'-edited.docx');edit.write_bytes(data)
                    report.setdefault('edits',[]).append({'case':name,'artifact':edit.name,'sha256':sha(edit),'change':change})
                    pdf=convert(edit.name,data,'pdf',name,'edited-office');convert(pdf.name,pdf.read_bytes(),'txt',name,'edited-text')
                print(name,'chain completed',flush=True)
            report['status']='completed'
        except BaseException as error:
            report['status']='failed';report['failures'].append({'type':type(error).__name__,'message':str(error)});raise
        finally:
            try:report['supervision']=process.shutdown()
            finally:stop.set();observer.join(timeout=2)
            report['workerPids']=sorted(workers);now=snapshot()
            report['newZombies']=[r for pid,r in now.items() if r['state']=='Z' and (pid not in baseline or baseline[pid]['startTicks']!=r['startTicks'])]
            finish=resource.getrusage(resource.RUSAGE_CHILDREN)
            report['resources']={'wallSeconds':time.monotonic()-started,'userSeconds':finish.ru_utime-cpu.ru_utime,'systemSeconds':finish.ru_stime-cpu.ru_stime,'maxChildRssKiB':finish.ru_maxrss}
            if report['newZombies'] or len(workers)!=len(report['cases']):
                report['status']='failed'
                report['failures'].append({'type':'FinalObservationFailure','workers':len(workers),'contracts':len(report['cases']),'newZombies':report['newZombies']})
            save();assert not report['newZombies'];assert len(workers)==len(report['cases'])
if __name__=='__main__':main()
