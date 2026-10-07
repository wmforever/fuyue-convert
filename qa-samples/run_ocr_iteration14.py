#!/usr/bin/env python3
"""Execute only the frozen finite candidate diagnostic, retaining every raw run."""
import hashlib
import json
import os
from pathlib import Path
import resource
import shutil
import subprocess
import time
from PIL import Image
from verify_cloud_ocr import metrics

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'qa-samples/generated/cloud-iteration14'
OUT=ROOT/'qa-samples/work/iteration14-native'
RUNTIME=ROOT/'desktop/.runtime/ocr'
CP='qa-samples/work/iteration14-classes:task-service/target/classes:layout-model/target/classes:qa-samples/work/iteration9-threshold/deps/*'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def text(result): return '\n'.join(b['text'] for b in result['blocks'])
def write(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')

def main():
    assert not OUT.exists(),'Existing results: inspect and resume missing work manually; never overwrite'
    OUT.mkdir(parents=True)
    started=time.monotonic()
    plan=json.loads((ROOT/'docs/cloud-ocr-iteration14-plan.json').read_text())
    limits=plan['newRunLimits'];manifest=json.loads((SOURCE/'expected.json').read_text())
    old_native=json.loads((ROOT/'qa-samples/work/iteration11-native/results.json').read_text())
    old_enhanced=json.loads((ROOT/'qa-samples/work/iteration11-dpi/results.json').read_text())
    count=0
    report={'sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'planSha256':sha(ROOT/'docs/cloud-ocr-iteration14-plan.json'),'manifestSha256':sha(SOURCE/'expected.json'),
        'jarSha256':sha(ROOT/'web-api/target/web-api-0.1.5.jar'),
        'productionChanged':False,'scope':'CLI/actual-Java selector diagnostic; no HTTP/Word/production acceptance claim',
        'versions':{'Java':'Temurin17.0.16+8','Tesseract':'5.5.2','Leptonica':'1.87.0','libpng':'1.6.57','zlib':'1.3.1',
                    'tessdata_fast':'87416418657359cb625c412a48b6e1d6d41c29bd','fonts':manifest['fonts'],'generator':manifest['versions']},
        'hashes':{'binary':sha(RUNTIME/'bin/tesseract'),'eng':sha(RUNTIME/'tessdata/eng.traineddata'),
                  'chi_sim':sha(RUNTIME/'tessdata/chi_sim.traineddata')},
        'selectorSources':{name:sha(ROOT/'task-service/src/main/java/com/fuyue/formatconverter/task'/name)
                           for name in ['TesseractOcrConverter.java','OcrPartialRecovery.java','OcrContrastEnhancer.java']},
        'cases':[],'newCliRuns':0,'status':'running','failures':[]}
    def remaining():
        value=limits['wholeDiagnosticSeconds']-(time.monotonic()-started)
        if value<=0:raise TimeoutError('Frozen whole diagnostic bound exceeded')
        return value
    def java(mode,source,out,*budgets):
        directory=out/mode;directory.mkdir()
        cmd=['java','-cp',CP,'com.fuyue.formatconverter.task.OcrPsmCandidateProbe',mode,str(source),str(out),str(RUNTIME),*map(str,budgets)]
        start=time.monotonic()
        with (directory/'stdout.log').open('xb') as stdout,(directory/'stderr.log').open('xb') as stderr:
            try:
                process=subprocess.run(cmd,cwd=ROOT,stdout=stdout,stderr=stderr,timeout=min(30,remaining()))
                result={'command':cmd,'exitCode':process.returncode,'seconds':time.monotonic()-start}
            except Exception as e:
                result={'command':cmd,'seconds':time.monotonic()-start,'error':str(e)}
                write(directory/'command.json',result);raise
        write(directory/'command.json',result)
        assert result['exitCode']==0,result
        return result
    def cli(image,out,variant,psm):
        nonlocal count
        assert count<limits['maximumCliRuns'],'Frozen CLI count exceeded'
        count+=1;report['newCliRuns']=count
        directory=out/variant;directory.mkdir()
        cmd=[str(RUNTIME/'bin/tesseract'),str(image),str(directory/'ocr'),'--tessdata-dir',str(RUNTIME/'tessdata'),'-l','chi_sim+eng','--psm',str(psm),'tsv']
        start=time.monotonic();bound=min(limits['perCommandSeconds'],remaining());timed_out=False
        def address_limit():resource.setrlimit(resource.RLIMIT_AS,(limits['cliAddressSpaceBytes'],limits['cliAddressSpaceBytes']))
        with (directory/'stdout.log').open('xb') as stdout,(directory/'stderr.log').open('xb') as stderr:
            process=subprocess.Popen(cmd,cwd=ROOT,stdout=stdout,stderr=stderr,preexec_fn=address_limit)
            while True:
                pid,status,usage=os.wait4(process.pid,os.WNOHANG)
                if pid:break
                if time.monotonic()-start>=bound:
                    process.kill();pid,status,usage=os.wait4(process.pid,0);timed_out=True;break
                time.sleep(.02)
            process.returncode=os.waitstatus_to_exitcode(status)
        result={'command':cmd,'seconds':time.monotonic()-start,'userSeconds':usage.ru_utime,'systemSeconds':usage.ru_stime,
            'peakRssKiB':usage.ru_maxrss,'exitCode':process.returncode,'timeout':timed_out,'cached':False,
            'stdoutSha256':sha(directory/'stdout.log'),'stderrSha256':sha(directory/'stderr.log')}
        if (directory/'ocr.tsv').exists():result['tsvSha256']=sha(directory/'ocr.tsv')
        write(directory/'command.json',result)
        assert not timed_out and process.returncode==0,result
        assert usage.ru_maxrss<=limits['maximumObservedPeakRssKiBForAdoption'],result
        shutil.copyfile(directory/'ocr.tsv',out/(variant+'.tsv'))
        return result
    try:
        for case in manifest['cases']:
            image=SOURCE/case['file'];assert sha(image)==case['sha256']
            out=OUT/case['file'];out.mkdir()
            rec={'file':case['file'],'inputSha256':case['sha256'],'expectedLines':case['expectedLines'],
                 'layout':case['layout'],'commands':{},'skipped':[]}
            report['cases'].append(rec);write(OUT/'results.json',report)
            rec['prepareCommand']=java('prepare',image,out)
            prep=json.loads((out/'preparation.json').read_text());rec['preparation']=prep
            if prep['enhancedAvailable']:
                with Image.open(out/'enhanced.png') as enhanced:
                    assert enhanced.size==(2400,1500) and not enhanced.info.get('dpi')
                    rec['enhancedPixelSha256']=hashlib.sha256(enhanced.tobytes()).hexdigest()
            if case['reusedOriginalPsm3']:
                old=next(c for c in old_native['cases'] if c['file']==case['sourceFile'])
                assert old['sourceSha256']==case['sourceSha256']==case['sha256']
                path=ROOT/'qa-samples/work/iteration11-native'/case['sourceFile']/'original.tsv'
                shutil.copyfile(path,out/'original.tsv')
                rec['commands']['original']={**old['nativeCommand'],'cached':True,'tsvSha256':sha(path),'cachedTsv':str(path.relative_to(ROOT))}
            else:rec['commands']['original']=cli(image,out,'original',3)
            if prep['enhancedAvailable']:
                if case['reusedEnhancedPsm3']:
                    old=next(c for c in old_enhanced['cases'] if c['file']==case['sourceFile'])
                    assert old['sourceSha256']==case['sha256'] and old['enhancedPixelSha256']==rec['enhancedPixelSha256']
                    path=ROOT/'qa-samples/work/iteration11-dpi'/case['sourceFile']/'without-dpi.tsv'
                    shutil.copyfile(path,out/'enhanced-psm3.tsv')
                    rec['commands']['enhanced-psm3']={**old['commands']['without-dpi'],'cached':True,'tsvSha256':sha(path),'cachedTsv':str(path.relative_to(ROOT))}
                else:rec['commands']['enhanced-psm3']=cli(out/'enhanced.png',out,'enhanced-psm3',3)
                rec['commands']['enhanced-psm6']=cli(out/'enhanced.png',out,'enhanced-psm6',6)
            else:rec['skipped']=['Actual enhancer returned null: both enhanced PSM3 and PSM6 unrun']
            durations=rec['commands'];total=durations['original']['seconds']+prep['seconds']
            remaining3=120-total-durations.get('enhanced-psm3',{}).get('seconds',0)
            remaining6=remaining3-durations.get('enhanced-psm6',{}).get('seconds',0)
            rec['counterfactualBudgetNote']='Cached+new CLI times only; non-contemporaneous, exclude service/worker startup and actual Java parsing overhead; not production deadline acceptance'
            rec['evaluateCommand']=java('evaluate',image,out,remaining3,remaining6)
            sel=json.loads((out/'selection.json').read_text());rec['selection']=sel
            truth='\n'.join(case['expectedLines']);rec['originalMetrics']=metrics(truth,text(sel['original']))
            for variant in sel['variants'].values():
                variant['candidateMetrics']=metrics(truth,text(variant['candidate']))
                variant['selectedMetrics']=metrics(truth,text(variant['selected']))
            baseline=sel['variants'].get('enhanced-psm3',{}).get('selected',sel['original'])
            rec['baselineSelectedMetrics']=metrics(truth,text(baseline))
            rec['conditionalMetrics']=metrics(truth,text(sel['conditionalSelected']))
            report['diagnosticWallSeconds']=time.monotonic()-started
            write(out/'record.json',rec);write(OUT/'results.json',report)
            print(json.dumps({'file':case['file'],'newCliRuns':count,'eligible':sel['productionRetryEligible'],
                'originalCER':rec['originalMetrics']['cer'],'conditionalCER':rec['conditionalMetrics']['cer'],
                'conditionalAdoption':sel['conditionalAdoption'],
                'variants':{name:{'confidence':v['candidate']['confidence'],'CER':v['candidateMetrics']['cer'],
                    'gainGate':v['gainGate'],'fullAccepted':v['fullActualAccepted'],'partialAccepted':v['partialAccepted'],'adoption':v['adoption']}
                    for name,v in sel['variants'].items()}},ensure_ascii=False),flush=True)
        report['status']='completed'
    except Exception as error:
        report['status']='failed-stopped';report['failures'].append({'type':type(error).__name__,'message':str(error)})
        raise
    finally:
        report['diagnosticWallSeconds']=time.monotonic()-started;write(OUT/'results.json',report)

if __name__=='__main__':main()
