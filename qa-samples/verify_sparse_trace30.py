#!/usr/bin/env python3
"""Read the completed finite diagnostic, including its preserved initial labels."""
import hashlib,json
from pathlib import Path
from PIL import Image
from verify_cloud_ocr import metrics
from compare_text_iteration24 import numeric_tokens
from qa_process_guard import matches
ROOT=Path(__file__).resolve().parents[1]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def pixels(p):
    with Image.open(p) as image:
        r,g,b=image.convert('RGB').split();alpha=Image.new('L',image.size,255)
        return hashlib.sha256(Image.merge('RGBA',(alpha,r,g,b)).tobytes()).hexdigest()
def main():
    corpus=ROOT/'qa-samples/generated/sparse-trace30';reportdir=ROOT/'qa-samples/report/iteration30-sparse'
    m=json.loads((corpus/'expected.json').read_text());report=json.loads((reportdir/'report.json').read_text())
    trace=json.loads((ROOT/'qa-samples/work/iteration30-trace/trace.json').read_text())
    assert report['manifestSha256']==sha(corpus/'expected.json') and len(report['cases'])==10
    assert report['supervision']['waitpidNoChildren'] and not report['newZombies']
    assert len(report['workerIdentities'])==10 and all(not matches(v) for v in report['workerIdentities'])
    rows=[]
    for action,c in zip(m['actions'],report['cases'],strict=True):
        assert action['id']==c['case']
        t=c['task'];row=dict(input=action['input'],case=c['case'],status=t['status'],errorCode=t['errorCode'],seconds=c['seconds'])
        if action['input'] in ['native-backed.ofd','blank.ofd','noise.ofd']:
            assert t['status']=='FAILED' and not t['downloadReady'] and 'artifact' not in c
            assert t['errorCode']==('OCR_NO_NEW_TEXT' if action['input']=='native-backed.ofd' else 'OCR_NO_TEXT')
        else:
            assert t['status']=='SUCCESS' and t['downloadReady'];p=reportdir/c['artifact'];assert sha(p)==c['sha256']
            truth=m['truth']['original' if action['input'].startswith('original') else 'independent'];text=p.read_text()
            assert text==truth and numeric_tokens(text)==numeric_tokens(truth)
            row.update(text=text,metrics=metrics(truth,text),numericLexemesExact=True,warnings=t['warnings'])
        rows.append(row)
    for row in trace['rows']:
        image=corpus/('original.png' if row['case']=='original' else row['case']+'.png')
        assert pixels(image)==row['pixelSha256']==pixels(ROOT/'qa-samples/work/iteration30-trace'/row['case']/'normalized.png')
    original=trace['rows'][0];assert original['usable'] and original['requiredImages']==1
    assert all(r['duplicateNative'] for r in original['deduplication'])
    value=metrics(m['truth']['original'],'\n'.join(b['text'] for b in original['recognition']['blocks']))
    assert value['cer']==0 and value['alignedCharacterRecall']==1
    # The first frozen generator used the same output name for PNG/PDF. Both
    # completed records retain independent task IDs/Workers and identical SHA.
    # These equal outputs can share a payload; no requests need to be repeated.
    assert report['cases'][0]['sha256']==report['cases'][1]['sha256']
    assert report['cases'][2]['sha256']==report['cases'][3]['sha256']
    result=dict(parentRevision='c97de2a2fc05b177b2d34fef525d3637b3d96667',jarSha256=report['jarSha256'],
        manifestSha256=report['manifestSha256'],traceSha256=sha(ROOT/'qa-samples/work/iteration30-trace/trace.json'),
        uniqueTraceRecognitions=trace['uniqueRecognitions'],normalizedPixelsExactAllSeven=True,rows=rows,
        originalRecognitionMetrics=value,originalAllFourLinesDeduplicated=True,
        labelCorrection='Initial PNG/PDF action labels collided; independent recorded tasks produced identical payload SHA256s. Future generator labels include the input extension. Frozen executed manifest unchanged; no HTTP rerun.',
        decision='Sparse original is a conservative native-coverage/completeness refusal, not a pixel extraction/scaling/recognition loss. No strictness or confidence/numeric gate changed. Pivot to independently reproduced heading font-metric mismatch.',
        resources=report['resources'],allWorkersExited=True,ECHILD=True,newZombies=[],helperSha256=sha(Path(__file__)))
    (ROOT/'docs/cloud-sparse-trace30-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    checkpoint=dict(iteration=30,state='sparse root cause proven; heading font-metric fix investigation',parent=result['parentRevision'],
        sparse=result,activeCommands=[],branch='improve/cloud-ocr-completeness-20261003',pr='https://github.com/wmforever/fuyue-convert/pull/1')
    (ROOT/'qa-samples/work/cloud-checkpoint/ITERATION30-ROOT.json').write_text(json.dumps(checkpoint,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(verified=True,http=10,success=7,strictFailures=3,pixelsExact=7)))
if __name__=='__main__': main()
