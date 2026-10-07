#!/usr/bin/env python3
"""Sequential finite original/current comparison; never select or rewrite OCR by truth."""
import argparse,hashlib,json,re,time,resource,sys,csv,itertools
from pathlib import Path
from qa_process_guard import ManagedProcess,install_shutdown_handlers
ROOT=Path(__file__).resolve().parents[1]
def distance(a,b):
    prev=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        row=[i]
        for j,y in enumerate(b,1):row.append(min(row[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
        prev=row
    return prev[-1]
def metrics(truth,actual):
    a=re.sub(r'\s+','',truth);b=re.sub(r'\s+','',actual)
    numeric=lambda s:re.findall(r'[+-]?(?:\d+(?:[.,:/-]\d+)*|\.\d+)(?:[%‰‱])?',s)
    return dict(expected=truth,actual=actual,truthChars=len(a),charErrors=distance(a,b),contentCER=distance(a,b)/len(a) if a else (0 if not b else None),exactTextWithoutWhitespace=a==b,expectedNumericTokens=numeric(truth),actualNumericTokens=numeric(actual),exactNumericSequence=numeric(truth)==numeric(actual),exactLines=sum(line in actual.splitlines() for line in truth.splitlines()))
def main():
    if len(sys.argv)>1 and sys.argv[1]=='--one':
        _,_,classpath,source,folder,edge,cost=sys.argv;folder=Path(folder);started=time.monotonic()
        with ManagedProcess(['java','-cp',classpath,'com.fuyue.formatconverter.task.OcrScaleProbe38',source,str(folder),edge],receipt=folder.with_name(folder.name+'-java-receipt.json')) as child:rc=child.process.wait(timeout=145)
        used=resource.getrusage(resource.RUSAGE_CHILDREN)
        Path(cost).write_text(json.dumps(dict(wallSeconds=time.monotonic()-started,userSeconds=used.ru_utime,systemSeconds=used.ru_stime,maxRssKiB=used.ru_maxrss,scope='fresh Python child rusage of reaped Java/supervisor/engine descendants; max, not simultaneous sum'))+'\n')
        raise SystemExit(rc)
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--classpath',required=True);p.add_argument('--reuse-first-tsv',type=Path);p.add_argument('--bilinear-only',action='store_true');p.add_argument('--original-only',action='store_true');p.add_argument('--png-source',action='store_true',help='diagnostic raw PNG; PDF-decoded raster is the production comparison');a=p.parse_args();install_shutdown_handlers();a.out.mkdir(exist_ok=False)
    corpus=ROOT/'qa-samples/generated/numeric-scale38';manifest=json.loads((corpus/'expected.json').read_text());rows=[];start=time.monotonic()
    for case in manifest['cases']:
        source=corpus/(case['id']+('.png' if a.png_source else '.pdf'));assert hashlib.sha256(source.read_bytes()).hexdigest()==manifest['sources'][source.name]
        for mode,edge in ([('bilinear1600',-1600)] if a.bilinear_only else [('original',0)] if a.original_only else [('current1600',1600),('original',0)]):
            if not rows and a.reuse_first_tsv:
                assert case['id']=='en-56' and mode=='current1600'
                assert a.png_source,'first saved TSV belongs to raw PNG diagnostic'
                old=a.reuse_first_tsv;tsv=old/'en-56-current1600/ocr/tesseract-page-0001.tsv'
                with tsv.open() as handle:words=[r for r in csv.DictReader(handle,delimiter='\t') if r['level']=='5' and r['text'].strip()]
                text='\n'.join(' '.join(w['text'] for w in line) for _,line in itertools.groupby(words,key=lambda r:(r['block_num'],r['par_num'],r['line_num'])))+'\n'
                rows.append(dict(id=case['id'],mode=mode,metrics=metrics(case['expected'],text),resource=json.loads((old/'en-56-current1600-cost.json').read_text()),model=None,recognitionSuccess=True,tsvCount=1,reusedCompletedEnglishTsv=str(tsv.relative_to(ROOT)),postOcrSerializationFailure=True,fullModelNotCaptured=True));continue
            folder=a.out/(case['id']+'-'+mode);receipt=a.out/(folder.name+'-receipt.json');log=a.out/(folder.name+'-command.log');cost=a.out/(folder.name+'-cost.json')
            command=[sys.executable,str(Path(__file__).resolve()),'--one',a.classpath,str(source),str(folder),str(edge),str(cost)]
            with log.open('x') as handle:
                with ManagedProcess(command,receipt=receipt,stdout=handle,stderr=handle) as child:rc=child.process.wait(timeout=150)
            assert rc==0,(folder,rc);record=json.loads((folder/'model.json').read_text());rows.append(dict(id=case['id'],mode=mode,metrics=metrics(case['expected'],record.get('text','')),resource=json.loads(cost.read_text()),model=str((folder/'model.json').relative_to(ROOT)),recognitionSuccess=record['success'],tsvCount=record['tsvCount']))
            (a.out/'results.json').write_text(json.dumps(dict(rows=rows,status='running',manifestSha256=hashlib.sha256((corpus/'expected.json').read_bytes()).hexdigest()),ensure_ascii=False,indent=2)+'\n')
            print(case['id'],mode,'CER',rows[-1]['metrics']['contentCER'],'numbers',rows[-1]['metrics']['exactNumericSequence'],flush=True)
    (a.out/'results.json').write_text(json.dumps(dict(rows=rows,status='completed',wallSeconds=time.monotonic()-start,manifestSha256=hashlib.sha256((corpus/'expected.json').read_bytes()).hexdigest()),ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':main()
