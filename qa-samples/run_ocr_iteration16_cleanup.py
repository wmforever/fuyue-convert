#!/usr/bin/env python3
"""Bounded Linux HTTP process/permit lifecycle audit; foreground controlled engine."""
import argparse,fcntl,hashlib,json,os,pathlib,secrets,signal,socket,subprocess,sys,threading,time,urllib.request,urllib.error
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'qa-samples/work/iteration16-cleanup'
def write(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def identity(pid):
    try:
        fields=(pathlib.Path('/proc')/str(pid)/'stat').read_text().rsplit(')',1)[1].split()
        return {'pid':pid,'state':fields[0],'parent':int(fields[1]),'startTicks':fields[19]}
    except (OSError,ValueError,IndexError):return None
def live(record):
    now=identity(record['pid']);return now is not None and now['startTicks']==record['startTicks'] and now['state']!='Z'

def main():
    global OUT
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=pathlib.Path,default=OUT);parser.add_argument('--remaining-only',action='store_true')
    args=parser.parse_args();OUT=args.out.resolve()
    assert not OUT.exists();OUT.mkdir(parents=True)
    started=time.monotonic();report={'sourceRevision':'2fe947d156b34339735de3078dd047ec665e8267','cases':[],'failures':[],'status':'running'}
    source=ROOT/'qa-samples/generated/cloud-iteration15/accepted-incomplete.png';complete=ROOT/'qa-samples/generated/cloud-iteration15/complete.tsv';original=ROOT/'qa-samples/generated/cloud-iteration15/original.tsv'
    report['inputSha256']=hashlib.sha256(source.read_bytes()).hexdigest();report['jarSha256']=hashlib.sha256((ROOT/'web-api/target/web-api-0.1.5.jar').read_bytes()).hexdigest()
    engine=OUT/'controlled-engine.py'
    engine.write_text('''#!/usr/bin/env python3
import json,os,pathlib,shutil,subprocess,sys,time
if '--version' in sys.argv:print('tesseract controlled-lifecycle-v1');sys.exit(0)
if '--list-langs' in sys.argv:print('List of available languages (1):\\neng');sys.exit(0)
root=pathlib.Path(os.environ['LIFECYCLE_CONTROL_ROOT']);mode=json.loads((root/'mode.json').read_text());image=pathlib.Path(sys.argv[1]);base=pathlib.Path(sys.argv[2])
retry='tesseract-enhanced-' in image.name
if mode['kind']=='success':shutil.copyfile(os.environ['LIFECYCLE_COMPLETE_TSV'],str(base)+'.tsv');sys.exit(0)
if mode['kind']=='enhanced-hang' and not retry:shutil.copyfile(os.environ['LIFECYCLE_ORIGINAL_TSV'],str(base)+'.tsv');sys.exit(0)
child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'])
def ident(pid):
 f=(pathlib.Path('/proc')/str(pid)/'stat').read_text().rsplit(')',1)[1].split();return {'pid':pid,'state':f[0],'parent':int(f[1]),'startTicks':f[19]}
data={'engine':ident(os.getpid()),'child':ident(child.pid),'enhancedInput':str(image) if retry else None}
(root/(mode['marker']+'.json')).write_text(json.dumps(data))
child.wait()
''');engine.chmod(0o700)
    sentinel=subprocess.Popen([sys.executable,'-c','import time;time.sleep(240)']);sentinel_identity=identity(sentinel.pid)
    def save():write(OUT/'results.json',report)
    def until(predicate,seconds,label):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if time.monotonic()-started>240:raise TimeoutError('whole audit240s')
            value=predicate()
            if value:return value
            time.sleep(.05)
        raise TimeoutError(label)
    def run_group(label,concurrency,ocr_seconds,task_seconds,scenarios):
        folder=OUT/label;folder.mkdir();slotdir=folder/'slots';slotdir.mkdir();data=folder/'data';owned={};stopped=threading.Event()
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        token=secrets.token_urlsafe(40);base=f'http://127.0.0.1:{port}'
        env={**os.environ,'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':str(port),'FORMAT_CONVERTER_API_TOKEN':token,
          'FORMAT_CONVERTER_DATA_ROOT':str(data),'FORMAT_CONVERTER_CONCURRENCY':str(concurrency),'FORMAT_CONVERTER_TIMEOUT':str(task_seconds)+'s',
          'FORMAT_CONVERTER_TESSERACT_BINARY':str(engine),'FORMAT_CONVERTER_OCR_ENABLED':'true','FORMAT_CONVERTER_OCR_LANGUAGES':'eng',
          'FORMAT_CONVERTER_OCR_TIMEOUT_SECONDS':str(ocr_seconds),'FORMAT_CONVERTER_OCR_MAX_CONCURRENCY':'1','FORMAT_CONVERTER_OCR_LOCK_DIR':str(slotdir),
          'LIFECYCLE_CONTROL_ROOT':str(folder),'LIFECYCLE_COMPLETE_TSV':str(complete),'LIFECYCLE_ORIGINAL_TSV':str(original)}
        def request(path,body=None,method=None):
            with urllib.request.urlopen(urllib.request.Request(base+path,data=body,headers={'X-Format-Converter-Token':token},method=method),timeout=15) as response:return response.read()
        def task(tid):return json.loads(request('/api/tasks/'+tid))
        def create(name):
            boundary='qa-'+secrets.token_hex(16);body=(f'--{boundary}\r\nContent-Disposition: form-data; name="targetFormat"\r\n\r\ntxt\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}.png"\r\nContent-Type: image/png\r\n\r\n').encode()+source.read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
            req=urllib.request.Request(base+'/api/tasks',data=body,headers={'X-Format-Converter-Token':token,'Content-Type':'multipart/form-data; boundary='+boundary})
            with urllib.request.urlopen(req,timeout=15) as response:return json.load(response)['taskId']
        def mode(kind,marker):write(folder/'mode.json',{'kind':kind,'marker':marker})
        def observe():
            while not stopped.wait(.02):
                descendants={process.pid}
                rows={}
                for path in pathlib.Path('/proc').iterdir():
                    if path.name.isdigit():
                        item=identity(int(path.name))
                        if item:rows[item['pid']]=item
                for _ in range(8):
                    more={pid for pid,item in rows.items() if item['parent'] in descendants}
                    if more<=descendants:break
                    descendants|=more
                for pid in descendants-{process.pid}:
                    item=rows.get(pid)
                    if item:owned[pid]=item
        def cleanup(tid,registered):
            taskdir=data/'tasks'/tid
            def clean():
                no_process=all(not live(r) for r in registered)
                files=[p for key in ['work','output'] for p in (taskdir/key).rglob('*') if p.is_file()]
                png=[p for p in folder.rglob('tesseract-enhanced-*.png')]
                available=True
                for slot in slotdir.glob('slot-*.lock'):
                    with slot.open('a') as f:
                        try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB);fcntl.lockf(f,fcntl.LOCK_UN)
                        except BlockingIOError:available=False
                return {'processesGone':no_process,'workOutputFiles':len(files),'temporaryPng':len(png),'permitAvailable':available} if no_process and not files and not png and available else None
            result=until(clean,10,'owned process/work/permit cleanup10s')
            snapshots=[task(tid) for _ in range(3)];assert all(s['status'] in ['CANCELLED','FAILED'] and not s['downloadReady'] for s in snapshots)
            request('/api/tasks/'+tid,method='DELETE');until(lambda:not taskdir.exists(),10,'DELETE task cleanup')
            assert live(sentinel_identity),'unrelated owned sidecar was killed'
            return {**result,'stableSnapshots':snapshots,'deleteRemovedDirectory':True,'sentinelAlive':True}
        def recovery(name):
            mode('success',name);tid=create(name);snapshot=until(lambda:(s if (s:=task(tid))['status'] in ['SUCCESS','FAILED'] else None),15,'subsequent recovery')
            assert snapshot['status']=='SUCCESS',snapshot
            text=request('/api/tasks/'+tid+'/download').decode('utf-8-sig')
            assert all(value in text for value in ['03121','00643','-417.85','2027-02-16','14 28']),text
            request('/api/tasks/'+tid,method='DELETE');return {'snapshot':snapshot,'text':text,'numericRecoveryVerified':True}
        with (folder/'server.stdout').open('x') as stdout,(folder/'server.stderr').open('x') as stderr:
            process=subprocess.Popen([sys.executable,'/workspace/fuyue-env/reap-run.py','java','-jar',str(ROOT/'web-api/target/web-api-0.1.5.jar')],env=env,stdout=stdout,stderr=stderr,start_new_session=True)
            observer=threading.Thread(target=observe,daemon=True);observer.start()
            try:
                def health():
                    try:return json.loads(request('/api/health'))
                    except Exception:return None
                h=until(health,40,'startup');assert h['ocr']['available'],h
                for index,kind in enumerate(scenarios):
                    name=label+'-'+str(index);mode('enhanced-hang' if kind=='enhanced-cancel' else 'original-hang',name)
                    tid=create(name);marker=until(lambda:(json.loads((folder/(name+'.json')).read_text()) if (folder/(name+'.json')).exists() else None),10,'engine marker')
                    registered=[marker['engine'],marker['child']];worker=identity(marker['engine']['parent']);assert worker is not None;registered.append(worker)
                    rec={'scenario':kind,'taskId':tid,'marker':marker,'worker':worker};report['cases'].append(rec);save()
                    if kind in ['queued-cancel','permit-wait-cancel']:
                        other=create(name+'-waiting');time.sleep(.4);other_state=task(other)
                        assert other_state['status']=='WAITING' if kind=='queued-cancel' else other_state['status']=='CONVERTING',other_state
                        if kind=='queued-cancel':assert other_state['stage']=='QUEUED'
                        else:
                            progress=data/'tasks'/other/'work/file-0001/worker-progress.json'
                            until(lambda:progress.exists() and json.loads(progress.read_text()).get('stage')=='PARSING',5,'waiting worker reached OCR parsing')
                            rec['permitWaitProgress']=json.loads(progress.read_text());rec['firstEngineStillLive']=live(marker['engine'])
                            assert rec['firstEngineStillLive']
                        rec['waitingInitialSnapshot']=other_state
                        request('/api/tasks/'+other+'/cancel',b'')
                        # Any second waiting worker also belongs to this server; identify it by worker request path.
                        others=[]
                        for pid,item in list(owned.items()):
                            try:
                                if other.encode() in (pathlib.Path('/proc')/str(pid)/'cmdline').read_bytes():others.append(item)
                            except OSError:pass
                        rec['waitingCancellation']=until(lambda:(s if (s:=task(other))['status']=='CANCELLED' else None),3,'waiting cancellation')
                        request('/api/tasks/'+tid+'/cancel',b'')
                        rec['waitingCleanup']=cleanup(other,others)
                    elif kind=='enhanced-cancel':
                        assert marker['enhancedInput'] and pathlib.Path(marker['enhancedInput']).is_file()
                        request('/api/tasks/'+tid+'/cancel',b'')
                    terminal=until(lambda:(s if (s:=task(tid))['status'] in ['CANCELLED','FAILED'] else None),15,'terminal')
                    expected='OCR_TIMEOUT' if kind=='original-page-timeout' else 'CONVERSION_TIMEOUT' if kind=='task-timeout' else 'TASK_CANCELLED'
                    assert terminal['errorCode']==expected,(expected,terminal)
                    rec['terminal']=terminal;rec['cleanup']=cleanup(tid,registered);rec['recovery']=recovery(name+'-recovery');save()
                    print(kind,'cleanup passed, recovery passed',flush=True)
            finally:
                # Terminate the backend child first; keep its subreaper alive until all descendants are reaped.
                for item in list(owned.values()):
                    if item['parent']==process.pid and live(item):os.kill(item['pid'],signal.SIGTERM)
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    for item in list(owned.values()):
                        if live(item):os.kill(item['pid'],signal.SIGKILL)
                    process.kill();process.wait(timeout=5)
                stopped.set();observer.join(timeout=1)
                report.setdefault('servers',[]).append({'label':label,'ownedProcessIdentities':list(owned.values()),'allOwnedGone':all(not live(x) for x in owned.values())});save()
    try:
        if not args.remaining_only:
            run_group('enhanced',1,30,20,['enhanced-cancel']*3)
            run_group('page-timeout',1,2,20,['original-page-timeout']*2)
            run_group('task-timeout',1,30,5,['task-timeout'])
        run_group('queued',1,30,20,['queued-cancel'])
        run_group('permit',2,30,20,['permit-wait-cancel'])
        report['status']='completed-negative-no-cleanup-fault'
    except Exception as error:
        report['status']='failed-stopped';report['failures'].append({'type':type(error).__name__,'message':str(error)});raise
    finally:
        sentinel.terminate();sentinel.wait(timeout=5);report['seconds']=time.monotonic()-started;save()
if __name__=='__main__':main()
