#!/usr/bin/env python3
"""Linux QA subprocess supervision. Never kill the reaper to meet a timeout.

Receipts contain process identities/status only, never command arguments or env.
The supervisor stays alive until waitpid reports ECHILD, including on SIGTERM.
An unkillable child causes an explicit blocked receipt, not an early exit.
"""
import ctypes,json,os,pathlib,signal,subprocess,sys,time

class HarnessTeardownBlocked(RuntimeError): pass

def identity(pid):
    try:
        f=(pathlib.Path('/proc')/str(pid)/'stat').read_text().rsplit(')',1)[1].split()
        return {'pid':pid,'state':f[0],'parent':int(f[1]),'startTicks':f[19]}
    except (OSError,ValueError,IndexError): return None

def matches(record):
    now=identity(record['pid'])
    return now is not None and now['startTicks']==record['startTicks']

def snapshot():
    return {int(p.name):r for p in pathlib.Path('/proc').iterdir()
            if p.name.isdigit() and (r:=identity(int(p.name))) is not None}

def save(path,record):
    temporary=path.with_suffix('.writing')
    temporary.write_text(json.dumps(record,indent=2)+'\n');temporary.replace(path)

def install_shutdown_handlers():
    def interrupt(signum,frame): raise KeyboardInterrupt('QA harness signal '+str(signum))
    signal.signal(signal.SIGTERM,interrupt);signal.signal(signal.SIGINT,interrupt)

class ManagedProcess:
    def __init__(self,args,*,receipt,env=None,stdout=None,stderr=None):
        if not hasattr(os,'pidfd_open') or not hasattr(signal,'pidfd_send_signal'):
            raise HarnessTeardownBlocked('Linux pidfd signalling is required before starting QA children')
        self.receipt=pathlib.Path(receipt).resolve()
        assert not self.receipt.exists(), 'Never overwrite a supervision receipt'
        self.process=subprocess.Popen([sys.executable,str(pathlib.Path(__file__).resolve()),
            '--supervise',str(self.receipt),'--',*map(str,args)],env=env,stdout=stdout,
            stderr=stderr,start_new_session=True)
        self.record=identity(self.process.pid)
        try:
            end=time.monotonic()+5
            while time.monotonic()<end:
                if self.receipt.exists():
                    item=json.loads(self.receipt.read_text())
                    if item.get('root'): return
                if self.process.poll() is not None: raise RuntimeError('Supervisor exited before root registration')
                time.sleep(.02)
            raise TimeoutError('Supervisor root registration5s')
        except BaseException:
            self.shutdown();raise

    @property
    def pid(self): return self.process.pid
    def poll(self): return self.process.poll()
    def __enter__(self): return self
    def __exit__(self,*exc): self.shutdown()

    def shutdown(self):
        if self.process.poll() is None and self.record and matches(self.record):
            os.kill(self.pid,signal.SIGTERM)
        try: self.process.wait(timeout=15)
        except subprocess.TimeoutExpired as error:
            # Leave the owning reaper alive. The caller must stop further work.
            raise HarnessTeardownBlocked('Reaper retained alive; inspect '+str(self.receipt)) from error
        item=json.loads(self.receipt.read_text()) if self.receipt.exists() else {}
        if item.get('status')!='reaped' or not item.get('waitpidNoChildren'):
            raise HarnessTeardownBlocked('No ECHILD proof; inspect '+str(self.receipt))
        remaining=[r for r in item['registered'] if matches(r)]
        if remaining or (self.record and matches(self.record)):
            raise HarnessTeardownBlocked('Matching PID records remain: '+json.dumps(remaining))
        return item

def supervise(path,args):
    if ctypes.CDLL(None,use_errno=True).prctl(36,1,0,0,0)!=0:
        raise OSError(ctypes.get_errno(),'PR_SET_CHILD_SUBREAPER')
    owner=os.getpid();stop_requested=False
    def stop(signum,frame):
        nonlocal stop_requested
        stop_requested=True
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    root=os.fork()
    if root==0:
        signal.signal(signal.SIGTERM,signal.SIG_DFL);signal.signal(signal.SIGINT,signal.SIG_DFL)
        try: os.execvp(args[0],args)
        except BaseException: os._exit(127)
    record={'supervisor':identity(owner),'root':identity(root),'registered':[],
            'reaped':[],'signals':[],'status':'running','rootExitCode':None,'waitpidNoChildren':False}
    owned={};term_sent=set();kill_sent=set();stopping_at=None
    while True:
        rows=snapshot();descendants={owner}
        while True:
            more={pid for pid,r in rows.items() if r['parent'] in descendants}
            if more<=descendants: break
            descendants|=more
        for pid in descendants-{owner}:
            if pid in rows: owned[(pid,rows[pid]['startTicks'])]=rows[pid]
        no_children=False
        while True:
            try:
                pid,status=os.waitpid(-1,os.WNOHANG)
                if pid==0: break
                code=os.waitstatus_to_exitcode(status)
                record['reaped'].append({'pid':pid,'exitCode':code})
                if pid==root: record['rootExitCode']=code
            except ChildProcessError:
                no_children=True;break
        if no_children:
            record.update(status='reaped',waitpidNoChildren=True,registered=list(owned.values()))
            save(path,record)
            code=record['rootExitCode']
            return 0 if stop_requested else code if code is not None and code>=0 else 1
        if stop_requested or record['rootExitCode'] is not None:
            if stopping_at is None: stopping_at=time.monotonic()
            # Root first prevents new work; adopted descendants stay owned here.
            ordered=sorted(owned.values(),key=lambda r:r['pid']!=root)
            for r in ordered:
                key=(r['pid'],r['startTicks']);now=identity(r['pid'])
                if not now or now['startTicks']!=r['startTicks'] or now['state']=='Z': continue
                # Revalidate ancestry membership before every signal. Never act on an old PID alone.
                if r['pid'] not in descendants: continue
                sig=None
                if key not in term_sent: sig=signal.SIGTERM;term_sent.add(key)
                elif time.monotonic()-stopping_at>2 and key not in kill_sent:
                    sig=signal.SIGKILL;kill_sent.add(key)
                if sig is not None:
                    try:
                        fd=os.pidfd_open(r['pid'])
                        try:
                            if not matches(r): continue
                            signal.pidfd_send_signal(fd,sig)
                            record['signals'].append({'identity':now,'signal':int(sig)})
                        finally: os.close(fd)
                    except ProcessLookupError: pass
            record['status']='stopping' if time.monotonic()-stopping_at<12 else 'blocked-retaining-reaper'
        record['registered']=list(owned.values());save(path,record);time.sleep(.01)

if __name__=='__main__':
    assert sys.argv[1]=='--supervise' and sys.argv[3]=='--'
    sys.exit(supervise(pathlib.Path(sys.argv[2]),sys.argv[4:]))
