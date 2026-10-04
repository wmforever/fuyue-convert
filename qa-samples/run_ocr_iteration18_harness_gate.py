#!/usr/bin/env python3
"""Two bounded real HTTP cases; prove zero new PID/zombie records before OFD QA."""
import argparse,hashlib,json,os,pathlib,secrets,socket,subprocess,sys,time,urllib.request
from qa_process_guard import ManagedProcess,identity,matches,snapshot,install_shutdown_handlers
ROOT=pathlib.Path(__file__).resolve().parents[1]
class InjectedFailure(RuntimeError): pass
def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=pathlib.Path,required=True)
    parser.add_argument('--resistant-only',action='store_true')
    args=parser.parse_args();out=args.out.resolve();assert not out.exists();out.mkdir(parents=True)
    install_shutdown_handlers();started=time.monotonic();baseline=snapshot()
    old=json.loads((ROOT/'qa-samples/work/iteration16-harness-residuals.json').read_text())['matchingRegisteredPidRecords']
    assert len(old)==10 and all(matches(r) and identity(r['pid'])['state']=='Z' and identity(r['pid'])['parent']==1 for r in old)
    report={'sourceRevision':'b14f792b6b4624d8ff7e8b0c154e8becff8ba323','baseline':list(baseline.values()),
        'oldAuditZombies':old,'cases':[],'status':'running','jarSha256':sha(ROOT/'web-api/target/web-api-0.1.5.jar'),
        'planSha256':sha(ROOT/'docs/cloud-ocr-iteration18-plan.json'),'failures':[]}
    if args.resistant_only:report['extensionSha256']=sha(ROOT/'docs/cloud-ocr-iteration18-harness-extension.json')
    assert report['jarSha256']=='70cb1d5ab5bda5756116a61cfc14480e6aa2c98a9bc33fca744028af05468b0e'
    def save():write(out/'results.json',report)
    def until(predicate,seconds,label):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if time.monotonic()-started>100:raise TimeoutError('whole gate100s')
            value=predicate()
            if value:return value
            time.sleep(.02)
        raise TimeoutError(label)
    engine=out/'controlled-engine.py'
    engine.write_text('''#!/usr/bin/env python3
import json,os,pathlib,shutil,signal,subprocess,sys,time
if '--version' in sys.argv:print('tesseract controlled-harness-gate');sys.exit(0)
if '--list-langs' in sys.argv:print('List of available languages (1):\\neng');sys.exit(0)
root=pathlib.Path(os.environ['HARNESS_GATE_ROOT']);duration=.2 if os.environ['HARNESS_GATE_CASE']=='success' else 60
resistant=os.environ['HARNESS_GATE_CASE']=='exception-resistant'
if resistant:signal.signal(signal.SIGTERM,signal.SIG_IGN)
code='import time;time.sleep('+str(duration)+')'
if resistant:code="import time,signal,pathlib;signal.signal(signal.SIGTERM,signal.SIG_IGN);pathlib.Path("+repr(str(root/'child-ready'))+").touch();time.sleep(60)"
child=subprocess.Popen([sys.executable,'-c',code])
if resistant:
 while not (root/'child-ready').exists():time.sleep(.01)
def ident(pid):
 f=(pathlib.Path('/proc')/str(pid)/'stat').read_text().rsplit(')',1)[1].split();return {'pid':pid,'state':f[0],'parent':int(f[1]),'startTicks':f[19]}
(root/'engine-marker.json').write_text(json.dumps({'engine':ident(os.getpid()),'child':ident(child.pid)}))
child.wait();shutil.copyfile(os.environ['HARNESS_GATE_TSV'],sys.argv[2]+'.tsv')
''');engine.chmod(0o700)
    sentinel=subprocess.Popen([sys.executable,'-c','import time;time.sleep(120)']);sentinel_id=identity(sentinel.pid)
    try:
        for case in (['exception-resistant'] if args.resistant_only else ['success','exception']):
            folder=out/case;folder.mkdir();token=secrets.token_urlsafe(40)
            with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
            env={**os.environ,'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':str(port),'FORMAT_CONVERTER_API_TOKEN':token,
                'FORMAT_CONVERTER_DATA_ROOT':str(folder/'data'),'FORMAT_CONVERTER_OCR_ENABLED':'true',
                'FORMAT_CONVERTER_OCR_LANGUAGES':'eng','FORMAT_CONVERTER_TESSERACT_BINARY':str(engine),
                'FORMAT_CONVERTER_OCR_MAX_CONCURRENCY':'1','FORMAT_CONVERTER_OCR_TIMEOUT_SECONDS':'30',
                'FORMAT_CONVERTER_OCR_LOCK_DIR':str(folder/'slots'),'HARNESS_GATE_ROOT':str(folder),
                'HARNESS_GATE_CASE':case,'HARNESS_GATE_TSV':str(ROOT/'qa-samples/generated/cloud-iteration15/complete.tsv')}
            def request(path,data=None,headers=None):
                with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=data,
                    headers={'X-Format-Converter-Token':token,**(headers or {})}),timeout=15) as response:return response.read()
            rec={'case':case};report['cases'].append(rec);save();managed=None
            with (folder/'server.stdout').open('x') as stdout,(folder/'server.stderr').open('x') as stderr:
                try:
                    command=['java','-jar',str(ROOT/'web-api/target/web-api-0.1.5.jar')]
                    if args.resistant_only:
                        root_wrapper=folder/'resistant-root.py'
                        root_wrapper.write_text('import signal,subprocess,sys\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\np=subprocess.Popen(sys.argv[1:]);p.wait()\n')
                        command=[sys.executable,str(root_wrapper),*command]
                    with ManagedProcess(command,
                            receipt=folder/'supervision.json',env=env,stdout=stdout,stderr=stderr) as managed:
                        rec['supervisor']=managed.record
                        def health():
                            try:return json.loads(request('/api/health'))
                            except Exception:return None
                        rec['health']=until(health,40,'startup');assert rec['health']['ocr']['available']
                        boundary='qa-'+secrets.token_hex(16);source=ROOT/'qa-samples/generated/cloud-iteration15/accepted-incomplete.png'
                        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="targetFormat"\r\n\r\ntxt\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="gate.png"\r\nContent-Type: image/png\r\n\r\n').encode()+source.read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
                        task=json.loads(request('/api/tasks',body,{'Content-Type':'multipart/form-data; boundary='+boundary}))
                        taskpath='/api/tasks/'+task['taskId'];rec['taskId']=task['taskId']
                        marker=until(lambda:json.loads((folder/'engine-marker.json').read_text()) if (folder/'engine-marker.json').exists() else None,10,'foreground child registration')
                        rec['marker']=marker;worker=identity(marker['engine']['parent']);assert worker
                        rec['worker']=worker;rec['preTeardown']=json.loads(request(taskpath));save()
                        if case.startswith('exception'):
                            assert matches(marker['engine']) and matches(marker['child']) and matches(worker)
                            raise InjectedFailure('after worker/engine/foreground child registration')
                        final=until(lambda:(t if (t:=json.loads(request(taskpath)))['status'] in ['SUCCESS','FAILED'] else None),15,'conversion')
                        assert final['status']=='SUCCESS',final;data=request(taskpath+'/download');(folder/'result.txt').write_bytes(data)
                        text=data.decode('utf-8-sig');assert all(n in text for n in ['03121','00643','-417.85','2027-02-16','14 28'])
                        rec.update(task=final,text=text,numericRecovery=True)
                except InjectedFailure as error:
                    assert case.startswith('exception');rec['injectedException']={'type':type(error).__name__,'message':str(error)}
            rec['supervision']=json.loads((folder/'supervision.json').read_text())
            if args.resistant_only:
                root_pid=rec['supervision']['root']['pid']
                assert any(s['signal']==9 and s['identity']['pid']==root_pid for s in rec['supervision']['signals'])
                rec['resistantRootForcedAndReaped']=True
            identities=rec['supervision']['registered']+[rec['supervisor'],rec['marker']['engine'],rec['marker']['child'],rec['worker']]
            remaining=[r for r in identities if matches(r)]
            now=snapshot();new_z=[r for pid,r in now.items() if r['state']=='Z' and (pid not in baseline or baseline[pid]['startTicks']!=r['startTicks'])]
            assert not remaining and not new_z,(remaining,new_z)
            assert rec['supervision']['waitpidNoChildren'] and matches(sentinel_id)
            rec.update(allRegisteredPidRecordsAbsent=True,newZombieIdentities=new_z,unrelatedSidecarAlive=True);save()
            print(case,'ECHILD + all new PID records absent + zero new zombies',flush=True)
        report['status']='completed-success-and-exception-zero-new-records'
    except BaseException as error:
        report['status']='blocked-stop-process-heavy-work';report['failures'].append({'type':type(error).__name__,'message':str(error)});raise
    finally:
        sentinel.terminate();sentinel.wait(timeout=5)
        report['oldTenUnchanged']=all(matches(r) and identity(r['pid'])['state']=='Z' and identity(r['pid'])['parent']==1 for r in old)
        report['seconds']=time.monotonic()-started;save()

if __name__=='__main__':main()
