#!/usr/bin/env python3
"""One bounded encoding audit; no existing native run is repeated."""
import hashlib,json,os,resource,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];WORK=ROOT/'qa-samples/work';RUNTIME=WORK/'iteration25-review-app/ocr'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,r):p.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
def main():
    corpus=ROOT/'qa-samples/generated/encoding43';plan=json.loads((corpus/'expected.json').read_text());bounds=plan['bounds']
    pixels=json.loads((WORK/'iteration43-pixels/report.json').read_text());lookup={Path(c['input']).stem:c for c in pixels['cases']}
    prior=json.loads((WORK/'iteration41-native/report.json').read_text());old={c['id']:c for c in prior['cases']}
    out=WORK/'iteration43-native';out.mkdir(exist_ok=False);start=time.monotonic();calls=0
    assert sha(ROOT/'web-api/target/web-api-0.1.5.jar')=='1ead3c12b621c612535ba7a9f338915484b79be3b74509cf3c3b8a304b2ce68b'
    for item in plan['cases']:assert sha(corpus/item['file'])==item['sha256']
    # These exact inputs and stopping rule are persisted before recognition starts.
    frozen=[]
    for name in ['en-gradient','zh-gradient','en-normal','zh-normal']:
        rgb=corpus/(name+'-rgb.png');enh=WORK/'iteration43-pixels'/(name+'-rgb')/'enhanced.png'
        assert sha(enh)==lookup[name+'-rgb']['enhanced']['pngSha256']
        frozen.append(dict(id=name,original=str(rgb.relative_to(ROOT)),originalSha256=sha(rgb),enhanced=str(enh.relative_to(ROOT)),enhancedSha256=sha(enh)))
    write(out/'FROZEN.json',dict(cases=frozen,maximumNewNativeOCR=8,psm=3,language='chi_sim+eng',noNewProductionRetries=True,manifestSha256=sha(corpus/'expected.json'),pixelReportSha256=sha(WORK/'iteration43-pixels/report.json'),helperSha256=sha(Path(__file__))))
    report=dict(status='running',cases=[],newNativeOCR=0,extraReplayOCR=0,productionChanged=False)
    def save():write(out/'report.json',report)
    def remaining():
        value=bounds['wholeNativeSeconds']-(time.monotonic()-start)
        if value<=0:raise TimeoutError('whole native bound')
        return value
    def cli(image,folder,name):
        nonlocal calls
        assert calls<8;calls+=1;report['newNativeOCR']=calls;save();base=folder/name
        command=[str(RUNTIME/'bin/tesseract'),str(image),str(base),'--tessdata-dir',str(RUNTIME/'tessdata'),'-l','chi_sim+eng','--psm','3','tsv'];begin=time.monotonic()
        def limit():resource.setrlimit(resource.RLIMIT_AS,(bounds['addressSpaceBytes'],bounds['addressSpaceBytes']))
        with base.with_suffix('.stdout.log').open('x') as stdout,base.with_suffix('.stderr.log').open('x') as stderr:
            process=subprocess.Popen(command,cwd=ROOT,stdout=stdout,stderr=stderr,preexec_fn=limit)
            deadline=begin+min(bounds['perOCRSeconds'],remaining())
            while True:
                pid,status,usage=os.wait4(process.pid,os.WNOHANG)
                if pid:break
                if time.monotonic()>deadline:
                    process.kill();os.wait4(process.pid,0);raise TimeoutError('native per-command bound')
                time.sleep(.02)
            process.returncode=os.waitstatus_to_exitcode(status)
        row=dict(command=command,exitCode=process.returncode,seconds=time.monotonic()-begin,userSeconds=usage.ru_utime,systemSeconds=usage.ru_stime,peakRssKiB=usage.ru_maxrss,tsvSha256=sha(base.with_suffix('.tsv')))
        write(base.with_suffix('.command.json'),row);assert process.returncode==0 and usage.ru_maxrss<=bounds['maximumCliRssKiB'];return row
    try:
        for item in frozen:
            name=item['id'];folder=out/name;folder.mkdir();row=dict(id=name,cli=[]);report['cases'].append(row);save()
            row['cli'].append(cli(ROOT/item['original'],folder,'original'))
            row['cli'].append(cli(ROOT/item['enhanced'],folder,'enhanced'))
            for mode in ['original','enhanced']:
                old_tsv=WORK/'iteration41-native'/name/(mode+'.tsv')
                rec=next(x for x in old[name]['cli'] if x['command'][2].endswith('/'+mode))
                assert sha(old_tsv)==rec['tsvSha256'];row['prior'+mode.title()+'TsvSha256']=sha(old_tsv)
                row['originalNativeTsvByteExact']=old_tsv.read_bytes()==(folder/'original.tsv').read_bytes() if mode=='original' else row['originalNativeTsvByteExact']
            cp='qa-samples/work/iteration41-classes:task-service/target/classes:layout-model/target/classes:ofd-parser/target/classes:qa-samples/work/iteration39-classpath/BOOT-INF/lib/*'
            command=['java','-Xmx256m','-cp',cp,'com.fuyue.formatconverter.task.OcrLocalCandidateProbe41','evaluate-current',item['original'],str(folder),str(RUNTIME)]
            before=resource.getrusage(resource.RUSAGE_CHILDREN);begin=time.monotonic()
            with (folder/'evaluate.stdout.log').open('x') as stdout,(folder/'evaluate.stderr.log').open('x') as stderr:
                p=subprocess.run(command,cwd=ROOT,stdout=stdout,stderr=stderr,timeout=min(25,remaining()))
            after=resource.getrusage(resource.RUSAGE_CHILDREN);assert p.returncode==0
            write(folder/'evaluate-command.json',dict(command=command,exitCode=p.returncode,seconds=time.monotonic()-begin,userSeconds=after.ru_utime-before.ru_utime,systemSeconds=after.ru_stime-before.ru_stime))
            row['current']=json.loads((folder/'evaluate-current.json').read_text());save()
        report['status']='completed'
    except BaseException as error:
        report['status']='failed';report['error']=dict(type=type(error).__name__,message=str(error));raise
    finally:report['wallSeconds']=time.monotonic()-start;save()
    print('Completed four frozen encoding comparisons;8 new bundledOCR;0 repeated41OCR')
if __name__=='__main__':main()
