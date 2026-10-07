#!/usr/bin/env python3
"""Bounded OFD per-image warning contracts; controlled engine != native OCR quality."""
import argparse,hashlib,json,os,pathlib,resource,secrets,signal,socket,subprocess,sys,threading,time,urllib.request,zipfile
from verify_cloud_ocr import metrics
from qa_process_guard import ManagedProcess,install_shutdown_handlers
ROOT=pathlib.Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    install_shutdown_handlers()
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=pathlib.Path,required=True)
    parser.add_argument('--out',type=pathlib.Path,required=True);parser.add_argument('--scope',choices=['ofd'],required=True)
    args=parser.parse_args();out=args.out.resolve();assert not out.exists();out.mkdir(parents=True)
    manifest=json.loads((ROOT/'qa-samples/generated/cloud-iteration15/expected.json').read_text())
    frozen=json.loads((ROOT/'qa-samples/generated/cloud-iteration17/expected.json').read_text())
    frozen_hashes={c['file']:c['sha256'] for c in frozen['cases']}
    start=time.monotonic();report={'jarSha256':sha(args.jar),'scope':args.scope,'cases':[],'observedWorkerPids':[],
        'planSha256':sha(ROOT/'docs/cloud-ocr-iteration17-plan.json'),
        'fixtureManifestSha256':sha(ROOT/'qa-samples/generated/cloud-iteration17/expected.json'),
        'status':'running','failures':[]}
    def save(): (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    def server(engine,cases,label):
        folder=out/label;folder.mkdir();token=secrets.token_urlsafe(40)
        with socket.socket() as listener:listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
        env={**os.environ,'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':str(port),'FORMAT_CONVERTER_API_TOKEN':token,
            'FORMAT_CONVERTER_DATA_ROOT':str(folder/'data'),'FORMAT_CONVERTER_OCR_ENABLED':'true',
            'FORMAT_CONVERTER_OCR_MAX_CONCURRENCY':'1','FORMAT_CONVERTER_APP_HOME':str(ROOT/'desktop/.runtime'),
            'FORMAT_CONVERTER_OCR_LANGUAGES':'eng' if engine else 'chi_sim+eng'}
        if engine:env['FORMAT_CONVERTER_TESSERACT_BINARY']=str(engine)
        else:env.pop('FORMAT_CONVERTER_TESSERACT_BINARY',None)
        cmd=['java','-jar',str(args.jar.resolve())]
        worker_pids=set();stop=threading.Event()
        def observe():
            while not stop.wait(.05):
                for p in pathlib.Path('/proc').iterdir():
                    if not p.name.isdigit():continue
                    try:
                        text=(p/'cmdline').read_bytes()
                        if b'ConversionWorkerMain' not in text:continue
                        # Bind to this server through its parent chain, not an unrelated worker.
                        pid=int(p.name);parent=pid
                        for _ in range(10):
                            fields=(pathlib.Path('/proc')/str(parent)/'stat').read_text().rsplit(')',1)[1].split()
                            parent=int(fields[1])
                            if parent==process.pid:worker_pids.add(pid);break
                            if parent<=1:break
                    except (OSError,ValueError,IndexError):pass
        base=f'http://127.0.0.1:{port}'
        def request(path,data=None,headers=None):
            h={'X-Format-Converter-Token':token,**(headers or {})}
            with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=h),timeout=30) as response:return response.read()
        def convert(name,data,target,contract):
            boundary='qa-'+secrets.token_hex(16)
            body=(f'--{boundary}\r\nContent-Disposition: form-data; name="targetFormat"\r\n\r\n{target}\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
            begun=time.monotonic();task=json.loads(request('/api/tasks',body,{'Content-Type':'multipart/form-data; boundary='+boundary}))
            path='/api/tasks/'+task['taskId']
            while time.monotonic()-begun<120:
                task=json.loads(request(path))
                if task['downloadReady'] or task['status'] in ['FAILED','CANCELLED']:break
                time.sleep(.1)
            else:raise TimeoutError('HTTP conversion120s')
            rec={'file':name,'target':target,'contract':contract,'seconds':time.monotonic()-begun,'task':task}
            if task['downloadReady']:
                data=request(path+'/download');artifact=folder/(name+'.'+target);artifact.write_bytes(data)
                rec['success']=True;rec['artifact']=str(artifact.relative_to(out));rec['artifactSha256']=sha(artifact)
                if target=='txt':rec['text']=data.decode('utf-8-sig')
                elif target=='docx':
                    import xml.etree.ElementTree as ET
                    with zipfile.ZipFile(artifact) as archive:
                        xml=ET.fromstring(archive.read('word/document.xml'));rec['editableText']=''.join(n.text or '' for n in xml.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t'))
                        rec['originalMediaSha256']={n:hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist() if n.startswith('word/media/')}
            else:rec['success']=False
            report['cases'].append(rec);save();return rec
        with (folder/'server.log').open('x') as log:
            process=ManagedProcess(cmd,receipt=folder/'supervision.json',env=env,stdout=log,stderr=subprocess.STDOUT)
            observer=threading.Thread(target=observe,daemon=True);observer.start()
            try:
                for _ in range(180):
                    try:health=json.loads(request('/api/health'));break
                    except Exception:
                        if process.poll() is not None:raise RuntimeError('Server exited; retained private log')
                        time.sleep(.2)
                else:raise TimeoutError('Startup36s')
                report.setdefault('health',{})[label]=health
                for case in cases:
                    if time.monotonic()-start>480:raise TimeoutError('Declared whole HTTP suite480s')
                    source=pathlib.Path(case['source']);assert sha(source)==case['sha256']
                    if case['contract']=='scan-chain':
                        scan=convert(source.name,source.read_bytes(),'pdf','source-scan-pdf');assert scan['success']
                        word=convert(source.name+'.source.pdf',(out/scan['artifact']).read_bytes(),'docx','scan-overlay-word');assert word['success']
                        pdf=convert(source.name+'.scan.docx',(out/word['artifact']).read_bytes(),'pdf','scan-office-pdf');assert pdf['success']
                        txt=convert(source.name+'.office.pdf',(out/pdf['artifact']).read_bytes(),'txt','scan-office-text');assert txt['success']
                        continue
                    rec=convert(case['file'],source.read_bytes(),case['target'],case['contract'])
                    if case.get('expectedFailure'):
                        assert not rec['success'] and any(f.get('errorCode') in ['OCR_NO_TEXT','OCR_LOW_CONFIDENCE'] for f in rec['task']['files'])
                    else:assert rec['success'],rec
                    if 'expectedLines' in case and rec['success']:
                        rec['metrics']=metrics('\n'.join(case['expectedLines']),rec.get('text',rec.get('editableText','')));save()
                    if case['target']=='docx':
                        pdf=convert(source.name+'.docx',(out/rec['artifact']).read_bytes(),'pdf','scan-word-office-reopen');assert pdf['success']
                        txt=convert(source.name+'.pdf',(out/pdf['artifact']).read_bytes(),'txt','scan-word-office-text');assert txt['success']
                    if case['contract']=='unaffected-text':
                        pdf=convert('ordinary.docx',(out/rec['artifact']).read_bytes(),'pdf','unaffected-docx-pdf');assert pdf['success']
                        txt=convert('ordinary.pdf',(out/pdf['artifact']).read_bytes(),'txt','unaffected-pdf-txt');assert txt['success']
            finally:
                try:supervision=process.shutdown()
                finally:stop.set();observer.join(timeout=2)
                assert worker_pids,'No independent production worker observed'
                report['observedWorkerPids']+=sorted(worker_pids)
                report.setdefault('serverExitCodes',{})[label]=supervision['rootExitCode']
                report.setdefault('supervision',{})[label]=supervision;save()
    try:
        fixtures=ROOT/'qa-samples/generated/cloud-iteration17'
        for name,engine,targets in [('incomplete','accepted-incomplete-engine.py',['txt','docx']),
                ('complete','accepted-complete-engine.py',['txt']),('mixed','mixed-engine.py',['txt','docx']),
                ('bilingual',None,['txt','docx'])]:
            source=fixtures/(name+'.ofd')
            cases=[{'file':source.name,'source':str(source),'sha256':frozen_hashes[source.name],
                    'target':target,'contract':'ofd-'+name} for target in targets]
            server(fixtures/engine if engine else None,cases,name)
        report['status']='completed'
    except Exception as error:
        report['status']='failed-stopped';report['failures'].append({'type':type(error).__name__,'message':str(error)});raise
    finally:
        usage=resource.getrusage(resource.RUSAGE_CHILDREN);report['resources']={'wallSeconds':time.monotonic()-start,
            'userSeconds':usage.ru_utime,'systemSeconds':usage.ru_stime,'peakRssKiB':usage.ru_maxrss};save()
        print(json.dumps({'scope':args.scope,'status':report['status'],'contracts':len(report['cases']),'workers':len(report['observedWorkerPids']),'resources':report['resources']}))

if __name__=='__main__':main()
