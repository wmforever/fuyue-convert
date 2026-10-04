#!/usr/bin/env python3
"""Run one frozen local-gain candidate, serially, with original/native result reuse."""
import hashlib,json,os,resource,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];WORK=ROOT/'qa-samples/work'
RUNTIME=WORK/'iteration25-review-app/ocr'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,r):p.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
def main():
    corpus=ROOT/'qa-samples/generated/shadow-local41';plan=json.loads((corpus/'expected.json').read_text());bounds=plan['bounds']
    out=WORK/'iteration41-native';out.mkdir(exist_ok=False);start=time.monotonic();calls=0
    assert sha(ROOT/'web-api/target/web-api-0.1.5.jar')==plan['comparison']['currentJarSha256']
    assert sha(ROOT/plan['comparison']['earlyAccuracyJar'])==plan['comparison']['earlyAccuracyJarSha256']
    current='qa-samples/work/iteration41-classes:task-service/target/classes:layout-model/target/classes:ofd-parser/target/classes:qa-samples/work/iteration39-classpath/BOOT-INF/lib/*'
    early='qa-samples/work/iteration41-classes:qa-samples/work/iteration41-early-classpath/BOOT-INF/lib/*'
    report=dict(status='running',manifestSha256=sha(corpus/'expected.json'),helperSha256=sha(Path(__file__)),cases=[],newNativeOcr=0,extraReplayOcr=0,productionChanged=False)
    def save():write(out/'report.json',report)
    def remaining():
        value=bounds['wholeDiagnosticSeconds']-(time.monotonic()-start)
        if value<=0:raise TimeoutError('frozen diagnostic deadline')
        return value
    def java(mode,input,dest,cp):
        command=['java','-Xmx256m','-cp',cp,'com.fuyue.formatconverter.task.OcrLocalCandidateProbe41',mode,str(input),str(dest),str(RUNTIME)]
        before=resource.getrusage(resource.RUSAGE_CHILDREN);begin=time.monotonic()
        with (dest/(mode+'.stdout.log')).open('x') as stdout,(dest/(mode+'.stderr.log')).open('x') as stderr:
            process=subprocess.run(command,cwd=ROOT,stdout=stdout,stderr=stderr,timeout=min(25,remaining()))
        after=resource.getrusage(resource.RUSAGE_CHILDREN)
        rec=dict(command=command,exitCode=process.returncode,seconds=time.monotonic()-begin,userSeconds=after.ru_utime-before.ru_utime,systemSeconds=after.ru_stime-before.ru_stime)
        write(dest/(mode+'-command.json'),rec);assert process.returncode==0,rec
        return json.loads((dest/(mode+'.json')).read_text())
    def cli(image,dest,name):
        nonlocal calls
        assert calls<bounds['maximumNativeCli'];calls+=1;report['newNativeOcr']=calls;save()
        base=dest/name;command=[str(RUNTIME/'bin/tesseract'),str(image),str(base),'--tessdata-dir',str(RUNTIME/'tessdata'),'-l','chi_sim+eng','--psm','3','tsv'];begin=time.monotonic()
        def limit():resource.setrlimit(resource.RLIMIT_AS,(bounds['addressSpaceBytes'],bounds['addressSpaceBytes']))
        with base.with_suffix('.stdout.log').open('x') as stdout,base.with_suffix('.stderr.log').open('x') as stderr:
            process=subprocess.Popen(command,cwd=ROOT,stdout=stdout,stderr=stderr,preexec_fn=limit)
            while True:
                pid,status,usage=os.wait4(process.pid,os.WNOHANG)
                if pid:break
                if time.monotonic()-begin>min(bounds['perCommandSeconds'],remaining()):
                    process.kill();pid,status,usage=os.wait4(process.pid,0);raise TimeoutError('native frozen command bound')
                time.sleep(.02)
            process.returncode=os.waitstatus_to_exitcode(status)
        rec=dict(command=command,exitCode=process.returncode,seconds=time.monotonic()-begin,userSeconds=usage.ru_utime,systemSeconds=usage.ru_stime,peakRssKiB=usage.ru_maxrss,tsvSha256=sha(base.with_suffix('.tsv')))
        write(base.with_suffix('.command.json'),rec);assert process.returncode==0 and usage.ru_maxrss<=bounds['maximumCliRssKiB'],rec
        return rec
    try:
        # Validate old/current enhancement identity before any new recognition.
        for case in plan['cases']:
            input=corpus/case['file'];assert sha(input)==case['sourceSha256']
            folder=out/case['id'];folder.mkdir();old=folder/'early';old.mkdir()
            preparation=java('prepare-current',input,folder,current);oldprep=java('prepare-early',input,old,early)
            assert preparation['current']['available']==oldprep['current']['available']
            if preparation['current']['available']:assert (folder/'enhanced.png').read_bytes()==(old/'enhanced.png').read_bytes()
            report['cases'].append(dict(id=case['id'],sourceSha256=case['sourceSha256'],preparation=preparation,earlyEnhancedInputByteExact=True,cli=[]));save()
        for case,row in zip(plan['cases'],report['cases']):
            input=corpus/case['file'];folder=out/case['id'];old=folder/'early'
            row['cli'].append(cli(input,folder,'original'))
            for name in ['enhanced','local']:
                if (folder/(name+'.png')).exists():row['cli'].append(cli(folder/(name+'.png'),folder,name))
            # Native inputs byte-identical; old parser/selector gets exact same TSVs.
            for name in ['original','enhanced','local']:
                if (folder/(name+'.tsv')).exists():(old/(name+'.tsv')).write_bytes((folder/(name+'.tsv')).read_bytes())
            row['current']=java('evaluate-current',input,folder,current)
            row['early']=java('evaluate-early',input,old,early);save()
        report['status']='completed'
    except BaseException as error:
        report['status']='failed';report['error']=dict(type=type(error).__name__,message=str(error));raise
    finally:report['wallSeconds']=time.monotonic()-start;save()
    print('Completed frozen cases',len(report['cases']),'nativeOCR',calls,'replayOCR0')
if __name__=='__main__':main()
