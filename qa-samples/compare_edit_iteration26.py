#!/usr/bin/env python3
"""Compare the frozen scan-edit runs without conflating API/native and visible text."""
from pathlib import Path
import copy, json, math, xml.etree.ElementTree as ET
from PIL import Image, ImageChops, ImageDraw
from verify_edit_iteration26 import parts, W, V
ROOT=Path(__file__).resolve().parents[1]
def main():
    paths=[ROOT/'qa-samples/work/iteration26-scan-before-visible',ROOT/'qa-samples/work/iteration26-scan-after-visible']
    reports=[json.loads((p/'evidence.json').read_text()) for p in paths]
    assert reports[0]['manifestSha256']==reports[1]['manifestSha256']
    rows=[]
    for before,after in zip(reports[0]['cases'],reports[1]['cases'],strict=True):
        name=before['case'];assert name==after['case']
        docs=[parts(ROOT/f'qa-samples/report/iteration26-scan-{stage}/{name}-word-result.docx') for stage in ['before','after']]
        trees=[ET.fromstring(d['word/document.xml']) for d in docs]
        for tree in trees:
            for mask in tree.iter(V+'rect'):
                if mask.get('id','').startswith('ocr-mask-'):
                    style=mask.get('style').replace('z-index:-251658751;','z-index:1;');mask.set('style',style)
        assert ET.tostring(trees[0])==ET.tostring(trees[1]),name+' geometry/text changed beyond mask layer'
        media=[{k:v for k,v in d.items() if k.startswith('word/media/')} for d in docs];assert media[0]==media[1]
        assert before['nativeWordBoxes']==after['nativeWordBoxes'],name+' native PDF layout changed'
        assert before['unchangedOutsideAmountRegion'] and after['unchangedOutsideAmountRegion']
        images=[Image.open(p/(name+'-edited-visible.png')).convert('RGB') for p in paths]
        assert images[0].size==images[1].size;diff=ImageChops.difference(*images);draw=ImageDraw.Draw(diff)
        for mask in after['masks']:
            styles=dict(x.split(':',1) for x in mask['style'].split(';') if ':' in x)
            x,y,w,h=[float(styles[k].removesuffix('pt'))*300/72 for k in ['margin-left','margin-top','width','height']]
            draw.rectangle((math.floor(x)-2,math.floor(y)-2,math.ceil(x+w)+2,math.ceil(y+h)+2),fill=(0,0,0))
        outside=diff.getbbox() is None
        assert outside,name+' pixels changed outside sampled masks'
        rows.append({'case':name,'editingNotPromised':after['editingNotPromised'],'geometryAndNativeWordBoxesUnchanged':True,'sourceMediaBytesUnchanged':True,'pixelsUnchangedOutsideSampledMasks':outside,'editPixelsUnchangedOutsideAmountRegion':True,'before':{k:before[k] for k in ['pageOcr','amountOcr','apiText']},'after':{k:after[k] for k in ['pageOcr','amountOcr','apiText']},'masks':after['masks'],'paintOrderBefore':before['paintOrder'],'paintOrderAfter':after['paintOrder']})
    result={'parentRevision':'ea5467d5f11748c6a4246abaeba5fa04ed62dbab','manifestSha256':reports[0]['manifestSha256'],'cases':rows,'runs':[{k:r[k] for k in ['jarSha256','helperSha256','runtimeManifestSha256','renderLibrary','dpi','httpResources','httpContracts','httpSuccess','workersAbsent','ECHILD']} for r in reports]}
    output=ROOT/'docs/cloud-edit-iteration26-results.json';output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps([{'case':r['case'],'beforeCer':r['before']['pageOcr']['metrics']['cer'],'afterCer':r['after']['pageOcr']['metrics']['cer'],'visibleAmountExact':r['after']['amountOcr']['numericLexemesExact'],'apiNumbersExact':r['after']['apiText']['numericLexemesExact']} for r in rows]))
if __name__=='__main__':main()
